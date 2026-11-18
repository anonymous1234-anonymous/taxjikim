package com.kh.back.taxupload.service;

import com.kh.back.auth.security.CustomUserDetails;
import com.kh.back.common.exception.ApiException;
import com.kh.back.taxupload.dto.PdfParseResponseDto;
import com.kh.back.taxupload.dto.TaxCategoryChange;
import com.kh.back.taxupload.dto.TaxServiceResponse;
import com.kh.back.taxupload.dto.TaxTransactionDto;
import com.kh.back.taxupload.dto.TaxTransactionDto.TaxInputRequest;
import com.kh.back.taxupload.dto.TaxTransactionDto.TaxServiceRequest;
import com.kh.back.taxupload.mapper.TaxMapper;
import com.kh.back.taxupload.validator.TaxValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class YearEndTaxServiceImpl implements YearEndTaxService {

    private final TaxValidator taxValidator;
    private final TaxMapper taxMapper;
    private final PythonPdfClient pythonPdfClient;

    /**
     * 분석 기본정보와 최초 거래 목록을 같은 트랜잭션에서 저장한다.
     */
    @Override
    @Transactional
    public TaxServiceResponse save(
            CustomUserDetails principal,
            TaxServiceRequest request
    ) {
        taxValidator.validateSaveRequest(request);

        Long taxInputId = request.getTaxInputId(); // request.getTaxInputID()로 분석 id를 꺼내고
        List<TaxTransactionDto> rows = request.getTransactions(); // request.getTransactions()로 거래 목록을 꺼냄
        boolean createAnalysis = taxInputId == null; // 분석 ID가 없으면 새로 생성한다.

        TaxInputRequest input; // 새로 만들거나 db에서 조회한 분석 기본정보를 담을 변수를 선언

        if (createAnalysis) {

            Long ownerId = principal == null ? null : principal.getUserId(); // 인증 정보가 없으면  ownerid  에 null을, 있으면 로그인한 사용자의 id를 넣음
            input = normalizeInput(request.getInput(), null, ownerId); // 요청에서 받은 기본정보를 새 분석에 맞게 정리하고 소유자 id를 반영한 결과를 input에 담음
            taxValidator.validateNormalizedInput(input);

            input.setSaveStatus("TEMP"); // 새분석의 상태를 임시 저장으로 설정
            taxMapper.insertInput(input); // 분석 기본정보를 db에 저장
            taxInputId = input.getTaxInputId(); // 저장하면서 생성된 분석 id를 꺼냄 , 이후 거래를 이분석에 연결할때 사용
        } else {

            input = requireInput(principal, taxInputId, true); // 로그인한 사용자가 기존 분석에 접근할수 있는지 확인, 기본정보를 가져옴 , 마지막 true 는 거래 저장이 진행되는 동안 다른 트랜잭션이 같은 분석 행을 수정하지 못하도록 잠금을 걸어 조회한다
        }

        if (rows == null) { // rows가 null이면 거래를 저장하는 단계로 넘어가지 않고 지금까지 준비한 input과 빈거래 목록을 응답으로 돌려줌
            return buildSaveResponse(input, List.of());
        }

        List<TaxTransactionDto> requested = new ArrayList<>(); // taxtransactiondto 타입의 거래 내역을 담을 빈리스트를 만들고 그 리스트를 requested라는 변수에 넣음


        int index = 0; // 첫번쨰 거래의 위치를 0으로 시작
        for (TaxTransactionDto source : rows) { // rows 에서 거래를 하나씩 꺼내 source에 담음
            taxValidator.validateSaveTransactionSource(source, taxInputId); // 요청으로 받은 원본  거래를 검사
            TaxTransactionDto row = copyStoredTransaction(source, taxInputId); // 원본 거래를 바탕으로 해당 분석 id가 들어간 저장용 거래 row를 만듬
            taxValidator.validateSaveTransaction(row, input.getTaxYear(), index); // 저장용 거래를 과세 연도와 목록 위치를 넘겨 검사
            requested.add(row); // 검사를 통과한 거래를 저장할 목록에 추가
            index++; // 다음 거래의 위치를 가리키도록 index를 1 늘림
        } // requested.add(row) 는 메모리에 있는 리스트에 추가하는 작업이며 실제 db 저장은 뒤의 taxmapper.insertTransactions(requested)에서 처리

        List<TaxTransactionDto> existing = taxMapper.findTransactions(taxInputId, null); // 해당분석 taxinputid에 이미 저장된 거래 전체를 db에서 조회해 existing에 담음. 두번쨰 인자의 null은 특정 거래 id로 좁히지 않고 조회한다는 뜻
        Integer expectedCount = request.getExpectedTransactionCount(); // 요청을 보낸 쪽이 이번거래를 추가하기 전에 이미 저장돼있다고 예상한 건수를 가져옴
        int previousCount = 0; // 응답에서 기존 거래를 몇번 건너뛸건지 정하는 변수 , 처음에는 0으로 두고 아래 코드에서 expectedcount가 있으면 그 값으로 바꿈. 기존 3건에 2건을 추가했다면 앞의 3건을 건너뛰고 새로 추가된 2건을 돌려줌

        if (expectedCount != null) { // 요청에 예상 건수가 있는지 확인 , 0도 값이므로 이블록에 들어옴
            taxValidator.validateSaveNewTransactionIds(rows); // 추가할 거래에 이미 발급된 거래 id가 붙어있지 않은지 검사
            taxValidator.validateSaveExpectedTransactionCount(expectedCount, existing.size()); // 요청의 예상 건수와 db에 실제로 저장된 건수를 비교해 검사
            previousCount = expectedCount; // 검사를 통과하면 요청의 예상 건수를 이후 처리의 기준으로 씀

            if (existing.size() > previousCount) { // ex: 사용자가 기존 3건에 2건을 추가하려고 했는데 저장은 성공하고 응답을 받지 못해 같은요청을 다시 보냈다고 가정했을떄, db에는 5건이 있고 요청에서 예상한 기존 건수는 3건이므로 5>3 이 참임

                List<TaxTransactionDto> added = existing.subList(previousCount, existing.size()); // db에서 조회한 거래 목록 existing 에서 기존 거래 previousCount 건을 건너뛴뒤 나머지 를 added에 담음 ,ex: 기존 거래가 3건이고 db에 총 5건이 있으면 sublist(3,5)가 됨 시작 인덱스 3은 포함하고 끝 인덱스 5는 제외하므로 4번째와 5번째 거래를 가져옴
                taxValidator.validateSaveRetriedTransactions(added, requested); //이미 db에 저장된 added 와 이번 요청에서 저장하려는 거래 requested 를 비교 , 일치하면 다음 줄로 진행, 일치하지 않으면 검증에서 예외 발생
                return buildSaveResponse(input, added);
            }

            if (requested.isEmpty()) { // 이번에 저장할 거래가 0건이면 분석 기본정보와 빈 거래 목록 []을 돌려줌
                return buildSaveResponse(input, List.of());
            }
        } else { // 요청에 "기존에 몇건이 저장돼있어 " 라는 숫자 expectedCount 가 없을때 실행
            if (taxValidator.matchesSavedTransactions(existing, requested)) { // ex: db에 거래 a,b 가 있고 이번 요청에도 똑같이 a,b가 들어오면 db에 있는 a,b 를 그대로 돌려줌
                return buildSaveResponse(input, existing);
            }

            taxValidator.validateSaveNoExistingTransactions(existing); // db에 이미 저장된 거래가 0건인지 확인 , 이줄 까지 왔는데 기존 거래가 하나라도 있으면 예외 발생
            taxValidator.validateSaveNewTransactionIds(rows); // 이번에 받은 거래에 거래 id가 붙어 있는지 확인 , 새로 저장할 거래는 아직 db에서 id를 받은 적이 없어야 하므로 id가 붙어 있으면 예외 발생
        }

        taxMapper.insertTransactions(requested); // requested에 모아둔 거래 내역을 db에 저장
        invalidateAfterTransactionChange(taxInputId); // 거래가 추가되면 이전 거래 목록으로  계산한 분석 결과가 오래된 값이 되며 해당 분석 taxinputid의 이전 계산 결과를 삭제, 분석의 수정 시각을 갱신 , 방금 저장한 거래 내역은 db에 남음

        List<TaxTransactionDto> stored = taxMapper.findTransactions(taxInputId, null); // 방금 저장을 마친뒤 , 해당 분석 taxinputid의 거래 전체를 db에서 다시 조회해 stored에 담음 , 두번째 값인 null은 특정 거래 id로 조회 범위를 좁히지 않는다는 뜻
        List<TaxTransactionDto> savedRows = previousCount == 0 ? stored : stored.subList(previousCount, stored.size()); // 응답으로 돌려줄 거래 목록을 정함 , 조건? 참일때 값: 거짓일때 값 형태 , previousCount가 0 이면 stored전체를 담음 , previouscount가 3이고 저장후 총 5건이면 sublist(3,5)로 앞의 3건을 건너뜀 그러면 이벤에 추가한 4번째와 5번째 거래가 savedrows에 들어감
        return buildSaveResponse(input, savedRows);
    }

    private TaxServiceResponse buildSaveResponse(
            TaxInputRequest input,
            List<TaxTransactionDto> transactions
    ) {
        return TaxServiceResponse.builder()
                .input(input)
                .transactions(transactions)
                .build();
    }

    @Override
    @Transactional
    public TaxInputRequest update(
            CustomUserDetails principal,
            Long taxInputId,
            TaxServiceRequest request
    ) {
        taxValidator.validateUpdateRequest(taxInputId, request); // upddate()가 수정작업을 시작하기전에 요청을 검사

        TaxInputRequest input = request.getInput(); // 요청에서 수정할 분석 기본정보를 꺼내 input에 담음
        List<TaxCategoryChange> changes = request.getChanges(); // 요청에서 거래 카테고리 변경 목록을 꺼내 changes에 담음
        boolean updateProfile = input != null; // 기본정보가 들어 있으면 true, 없으면 false , 아래 코드에서 기본정보를 수정할지 결정할때 씀
        boolean updateCategory = changes != null && !changes.isEmpty(); // 변경 목록이 있고 changes != null , 한건 이상 들어 있으면 ( !changes.isempty) true, changes 가 null이면 두번째 조건은 검사하지 않음

        TaxInputRequest existingInput = requireInput(principal, taxInputId, true); // 현재 사용자의 접근 권한을 확인하고 해당 분석의 db 행을 잠근 상태로 조회
        TaxInputRequest updatedInput = existingInput; // 수정 결과의 초기값으로 기존 기본 정보를 사용, 카테고리만 수정하는 요청이라면 이값이 그대로 쓰임

        if (updateProfile) { // 요청에 수정할 기본정보가 들어있을떄 실행
            updatedInput = normalizeInput(input, taxInputId, existingInput.getUserId()); // 요청의 기본정보를 정리하면서 기존 분석 id와 소유자 id를 적용
            taxValidator.validateNormalizedInput(updatedInput); // 정리한 기본정보가 입력 조건을 충족하는지 검사

            updatedInput.setSaveStatus(existingInput.getSaveStatus()); // 기존 저장 상태를 새 기본정보에 복사
            taxMapper.updateInput(updatedInput); // 검사를 마친 기본정보를 db에 반영
        }

        if (updateCategory) { // 카테고리 변경 요청이 있으면 아래 코드 실행
            Set<Long> seenTransactionIds = new HashSet<>(); // 이미 확인한 거래 id를 기록할 빈 집합을 만듬 , 같은거래를 한 요청에서 두번 변경하려는 경우를 검사할떄 씀
            List<TaxCategoryChange.Feedback> feedback = new ArrayList<>(); // 학습용 피드백을 한건씩 담을 빈 목록을 만듬

            for (TaxCategoryChange change : changes) { // 변경 요청을 하나씩 꺼내 change에 넣음 ex: 17 번 의 카테고리 를 식비로 바꿔주세요 가 한건
                taxValidator.validateCategoryChangeTarget(change, seenTransactionIds);  // 변경 대상이 유효한지 같은 거래 id가 요청에 중복해서 들어왔는지 검사

                TaxTransactionDto row = requireTransaction(taxInputId, change.transactionId()); // 해당 분석에서 거래 id로 실제 거래를 찾아 row에 담음, 거래를 찾을 수 없으면 예외 발생
                taxValidator.validateCategory(change.category(), row); // 요청한 새 카테고리를 해당 거래에 적용할수 있는지 검사

                feedback.add(new TaxCategoryChange.Feedback( // 검사에 통과하면 거래id , 거래유형, 거래 설명 , 새 카테고리를 묶어 feedback목록에 추가
                        row.getTransactionId(),
                        row.getTransactionType(),
                        row.getDescription(),
                        change.category()
                ));
            }

            // 검증을 마친 카테고리 목록을 한 번에 반영한다.
            taxValidator.validateUpdateCategoryResult(
                    taxMapper.updateCategories(taxInputId, changes),
                    changes.size()
            );

            invalidateAfterTransactionChange(taxInputId); // taxinputid는 어느 분석에 적용할지 지정 , ex: 미분류에서 의료비 로 바꿨다면 이정 카테고리로 계산한 결과를 지우고 수정한 거래는 db에 남게됨


            try {
                // 학습 후보 전달까지 성공해야 카테고리 변경을 커밋한다.
                pythonPdfClient.sendTrainingFeedback(feedback);
            } catch (RestClientException exception) {
                log.warn("재학습 후보 전달 실패: 분석번호={}", taxInputId, exception);

                throw new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        "TRAINING_FEEDBACK_FAILED",
                        "재학습 데이터 전달에 실패해 카테고리 수정을 취소했습니다."
                );
            }
        } else if (updateProfile) { // 카테고리 변경 요청이 없고 분석 기본정보 수정 요청이 있을때 실행
            taxMapper.deleteResult(taxInputId); // 이전 기본정보로 계산한 분석 결과를 db에서 삭제
        }

        return updatedInput; // 분석 기본정보를 반환, 기본정보를 수정했다면 수정된 값을 , 카테고리만 수정했다면 앞에서 조회한 기본정보를 반환
    }

    /**
     * 분석 전체 삭제와 거래 삭제를 처리한다.
     */
    @Override
    @Transactional
    public void delete(
            CustomUserDetails principal,
            Long taxInputId, // 삭제 대상이 속한 분석  id
            TaxServiceRequest request, // 삭제할 거래 id등 요청 내용을 담고 잇음
            boolean deleteAll // true 면 분석 전체, false면 지정한 거래만 삭제
    ) {
        taxValidator.validateDeleteRequest(taxInputId, request, deleteAll); // 삭제에 필요한 값이 제대로 들어왔는지 먼저 검사함

        Long transactionId = request.getTransactionId(); // 한건을 삭제할때 사용할 거래 id를 꺼냄 , ex: 17
        List<Long> requestedIds = request.getTransactionIds(); // 여러 건을 삭제할때 사용할 거래 id 목록을 꺼냄, [17,23]

        // 삭제 전에 분석 접근 권한을 확인하고 분석을 잠근다.
        requireInput(principal, taxInputId, true);

        if (deleteAll) { // deleteall이 true 일때 분석전체를 삭제하고 delete()를 끝내는 코드
            taxValidator.validateDeleteInputResult(taxMapper.deleteInput(taxInputId)); // taxmapper.deleteInput(taxinputid) 괄호 안쪽이 먼저 실행돼 , 해당 id의 분석을 db에서 삭제, 삭제된 행수를 돌려줌
            return; // taxvalidator.validateDeleteInputResult(...) 그 행 수를 받아 삭제가 정상적으로 됐는지 검사, 문제 있으면 예외 발생
        } // returnl; 검사를 통과하면 delete()를 바로 끝냄, 아래의 단건 일괄 거래 삭제코드는 실행되지 않음

        int deleted; // db에서 실제로 삭제된 거래 건수를 담을 변수를 선언, 아래 두분기중 실행되눈 쪽에서 값을 넣음
        if (requestedIds != null) { // 요청에 삭제할 거래 id목록이 들어 있으면 일괄 삭제로 진행

            deleted = taxMapper.deleteTransactionBatch(taxInputId, Set.copyOf(requestedIds)); // set.copyOf()로 중복 id 제거, ex: [17,17,23] 이면 거래 17번과 23번이 삭제 대상 , db에서 실제로 23번 만 삭제됐다면 deleted 에는 1이 들어감
        } else { // id 목록이 없으면 transactionid로 지정된 거래 한건을 삭제하는 분기로 들어감

            requireTransaction(taxInputId, transactionId); // 해당분석에 그 거래가 있는지 확인, 거래가 없으면 예외발생
            deleted = taxMapper.deleteTransaction(taxInputId, transactionId); // 지정된 거래를 db에서 삭제하고, 삭제된 행 수를 deleted에 담음
            taxValidator.validateDeleteTransactionResult(deleted); // 단건 삭제에서 정확히 1건이 삭제됐는지 검사, 1건이 아니면 예외 발생
        }

        if (deleted > 0) { // deleted는 db에서 실제로 삭제된 거래건수이며 1 이상이면 괄호 안을 실행, 0이면 건너뜀
            invalidateAfterTransactionChange(taxInputId); // 해당 분석의 이전 계산 결과를 삭제, 분석의 수정 시각을 갱신
        }
    }

    /**
     * PDF에서 거래 내역을 추출한다.
     * 추출한 거래의 DB 저장은 save에서 처리한다.
     */
    @Override
    public PdfParseResponseDto parse( // 처리결과는 pdfparseresponsedto 객체로 돌려줌
            CustomUserDetails principal,// 요청한 사용자 정보
            Long taxInputId, // 기존분석의 id, 기존 분석을 지정하지 않으면 null일수 있음
            MultipartFile file // 사용자가 업로드한 pdf 파일
    ) throws IOException { // 파일 을 읽는중 입출력 오류 발생시 호출한 쪽으로 전달될수있다
        if (taxInputId != null) { // 기존 분석 id가 있을때만 아래 검사를 실행
            requireInput(principal, taxInputId, false); // 해당 분석을 조회하고 사용자가 접근할수있는지 확인, 마지막 false는 db행 잠금없이 조회
        }

        try {
            byte[] bytes = taxValidator.validateParseFile(file);

            // Python에서 PDF 분석과 OCR을 함께 수행하도록 원본을 전달한다.
            PdfParseResponseDto response = pythonPdfClient.analyze(
                    bytes,
                    file.getOriginalFilename()
            );

            taxValidator.validateParseResponse(response);

            List<TaxTransactionDto> transactions = new ArrayList<>();
            for (TaxTransactionDto source : response.getTransactions()) {
                taxValidator.validateParseTransactionPresent(source);

                // PDF 추출 거래는 아직 DB에 저장되지 않았으므로 식별자를 비운다.
                source.setTaxInputId(null);
                source.setTransactionId(null);

                TaxTransactionDto row;
                try {
                    row = copyStoredTransaction(source, null);
                    taxValidator.validateParseTransaction(source, row);
                } catch (ApiException exception) {
                    transactions.add(markPdfReview(source, exception.getMessage(), "거래 내용을 확인해주세요."));
                    continue;
                }

                if (taxValidator.requiresPdfReview(source)) {
                    transactions.add(markPdfReview(source, source.getFailureReason(), "추출한 거래 내용을 확인해주세요."));
                    continue;
                }

                row.setActualAmount(row.getAmount());
                row.setPageNumber(source.getPageNumber());
                row.setRawText(source.getRawText());
                row.setValueKind(source.getValueKind());
                row.setAmountBasis(source.getAmountBasis());
                row.setTaxDeducted(source.getTaxDeducted());
                row.setDocumentYear(source.getDocumentYear());
                row.setParseSuccess(true);
                row.setNeedsReview(false);
                row.setExcluded(false);
                row.setFailureReason(null);
                transactions.add(row);
            }

            long reviewCount = transactions.stream()
                    .filter(taxValidator::requiresReviewOrIsExcluded)
                    .count();

            // 추출 결과와 저장 상태를 구분해 반환한다.
            response.setTransactions(transactions);
            response.setSavedCount(0);
            response.setPersisted(false);
            response.setTaxInputIdsByYear(new LinkedHashMap<>());

            log.info(
                    "PDF 파싱 완료. 거래={}건, 검토={}건, DB 저장 없음",
                    transactions.size(),
                    reviewCount
            );

            return response;
        } catch (IOException | RuntimeException exception) {
            log.warn("PDF 파싱 실패. 코드={}", failureCode(exception));
            throw exception;
        }
    }

    private TaxTransactionDto markPdfReview(TaxTransactionDto row, String reason, String fallbackReason) {
        // 검토 대상 거래는 응답에 포함하고 저장 대상에서 제외한다.
        row.setNeedsReview(true);
        row.setExcluded(true);

        String reviewReason = reason == null || reason.isBlank() ? fallbackReason : reason;
        String current = row.getFailureReason();

        if (current == null || current.isBlank()) {
            row.setFailureReason(reviewReason);
            return row;
        }

        if (current.equals(reviewReason)) {
            return row;
        }

        for (String existing : current.split(" / ")) {
            if (existing.equals(reviewReason)) {
                return row;
            }
        }

        row.setFailureReason(current + " / " + reviewReason);
        return row;
    }

    private TaxTransactionDto copyStoredTransaction(TaxTransactionDto source, Long taxInputId) {
        String type = source.getTransactionType();

        if (type == null || type.isBlank()) {
            type = "UNKNOWN";
        }

        String category = source.getCategory();

        if (category == null || category.isBlank()) {
            category = "UNCLASSIFIED";
        }

        // 실제 입출금액이 있으면 PDF 추출과 거래 저장에서 우선 사용한다.
        BigDecimal actualAmount = source.getActualAmount();
        BigDecimal amount = actualAmount == null ? source.getAmount() : actualAmount;

        return TaxTransactionDto.builder()
                .taxInputId(taxInputId)
                .transactionType(type)
                .transactionDate(source.getTransactionDate())
                .description(source.getDescription())
                .category(category)
                .preTaxIncome(source.getPreTaxIncome())
                .deductedTaxRate(source.getDeductedTaxRate())
                .amount(amount)
                .build();
    }

    private TaxInputRequest normalizeInput(
            TaxInputRequest source,
            Long taxInputId,
            Long ownerId
    ) {
        taxValidator.validateInputPresent(source);

        // 분석번호와 소유자는 서버가 확인한 값으로 설정한다.
        return TaxInputRequest.builder()
                .taxInputId(taxInputId)
                .userId(ownerId)
                .taxYear(source.getTaxYear())
                .businessRegistered(source.getBusinessRegistered())
                .dependentCount(
                        source.getDependentCount() == null ? 0 : source.getDependentCount()
                )
                .generalDonationAmount(
                        source.getGeneralDonationAmount() == null
                                ? BigDecimal.ZERO
                                : source.getGeneralDonationAmount()
                )
                .specialDonationAmount(
                        source.getSpecialDonationAmount() == null
                                ? BigDecimal.ZERO
                                : source.getSpecialDonationAmount()
                )
                .saveStatus(source.getSaveStatus() == null ? "TEMP" : source.getSaveStatus())
                .build();
    }

    private TaxInputRequest requireInput(CustomUserDetails principal, Long taxInputId, boolean lock) {
        taxValidator.validateId(taxInputId, "taxInputId");

        TaxInputRequest input = lock
                ? taxMapper.lockInput(taxInputId)
                : taxMapper.findInput(taxInputId);

        taxValidator.validateInputAccess(principal, input);
        return input;
    }

    private TaxTransactionDto requireTransaction(Long taxInputId, Long transactionId) { // 특정 분석에 속한 거래 한건을 찾아 반환하는 메서드
        List<TaxTransactionDto> rows = taxMapper.findTransactions(taxInputId, transactionId);

        taxValidator.validateTransactionFound(rows);
        return rows.get(0);
    }

    private void invalidateAfterTransactionChange(Long taxInputId) { // taxinputid로 어느 분석을 처리할지 받음

        taxMapper.deleteResult(taxInputId); // 해당 분석의 이전 계산 결과를 db에서 삭제 , ex: 거래의 카테고리를 바꿨다면 바꾸기전 카테고리로 계산한 결과를 지움
        taxMapper.touchInput(taxInputId); // 해당 분석의 마지막 수정 시각을 db에서 갱신
    }

    private String failureCode(Exception exception) {
        if (exception instanceof ApiException apiException) {
            return apiException.getCode();
        }

        return exception instanceof IOException ? "PDF_IO_ERROR" : "PDF_PROCESSING_FAILED";
    }
}
