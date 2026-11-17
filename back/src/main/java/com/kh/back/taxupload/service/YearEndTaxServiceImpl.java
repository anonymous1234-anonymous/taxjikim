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
        boolean createAnalysis = taxInputId == null; // createAnalysis  는 true 가 됨

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

        List<TaxTransactionDto> savedRows = rows == null
                ? List.of()
                : saveTransactions(taxInputId, input.getTaxYear(), rows, request.getExpectedTransactionCount());

        return TaxServiceResponse.builder()
                .input(input)
                .transactions(savedRows)
                .build();
    }

    private List<TaxTransactionDto> saveTransactions(
            Long taxInputId,
            Integer taxYear,
            List<TaxTransactionDto> sources,
            Integer expectedCount
    ) {
        List<TaxTransactionDto> requested = new ArrayList<>();

        // 원본의 저장 가능 여부를 확인한 뒤 저장용 거래를 만든다.
        int index = 0;
        for (TaxTransactionDto source : sources) {
            taxValidator.validateSaveTransactionSource(source, taxInputId);
            TaxTransactionDto row = copyStoredTransaction(source, taxInputId);
            taxValidator.validateSaveTransaction(row, taxYear, index);
            requested.add(row);
            index++;
        }

        List<TaxTransactionDto> existing = taxMapper.findTransactions(taxInputId, null);
        int previousCount = 0;

        if (expectedCount != null) {
            taxValidator.validateSaveNewTransactionIds(sources);
            taxValidator.validateSaveExpectedTransactionCount(expectedCount, existing.size());
            previousCount = expectedCount;

            if (existing.size() > previousCount) {
                // 재시도라면 이미 저장된 거래를 반환한다.
                List<TaxTransactionDto> added = existing.subList(previousCount, existing.size());
                taxValidator.validateSaveRetriedTransactions(added, requested);
                return added;
            }

            if (requested.isEmpty()) {
                return List.of();
            }
        } else {
            if (taxValidator.matchesSavedTransactions(existing, requested)) {
                return existing;
            }

            taxValidator.validateSaveNoExistingTransactions(existing);
            taxValidator.validateSaveNewTransactionIds(sources);
        }

        taxMapper.insertTransactions(requested);
        invalidateAfterTransactionChange(taxInputId);

        List<TaxTransactionDto> stored = taxMapper.findTransactions(taxInputId, null);
        return previousCount == 0 ? stored : stored.subList(previousCount, stored.size());
    }

    @Override
    @Transactional
    public TaxInputRequest update(
            CustomUserDetails principal,
            Long taxInputId,
            TaxServiceRequest request
    ) {
        taxValidator.validateUpdateRequest(taxInputId, request);

        TaxInputRequest input = request.getInput();
        List<TaxCategoryChange> changes = request.getChanges();
        boolean updateProfile = input != null;
        boolean updateCategory = changes != null && !changes.isEmpty();

        TaxInputRequest existingInput = requireInput(principal, taxInputId, true); // 현재 사용자의 접근 권한을 확인하고 해당 분석의 db 행을 잠근 상태로 조회
        TaxInputRequest updatedInput = existingInput; // 수정 결과의 초기값으로 기존 기본 정보를 사용, 카테고리만 수정하는 요청이라면 이값이 그대로 쓰임

        if (updateProfile) { // 요청에 수정할 기본정보가 들어있을떄 실행
            updatedInput = normalizeInput(input, taxInputId, existingInput.getUserId()); // 요청의 기본정보를 정리하면서 기존 분석 id와 소유자 id를 적용
            taxValidator.validateNormalizedInput(updatedInput); // 정리한 기본정보가 입력 조건을 충족하는지 검사

            updatedInput.setSaveStatus(existingInput.getSaveStatus()); // 기존 저장 상태를 새 기본정보에 복사
            taxMapper.updateInput(updatedInput); // 검사를 마친 기본정보를 db에 반영
        }

        if (updateCategory) { // 카테고리 변경 요청이 있으면
            applyCategoryChanges(taxInputId, changes); // applyCategoryChanges(taxInputID, changes)를 호출해 해당 분석의 거래 카테고리를 수정, 기본정보와 카테고리를 함께 수정하는 경우에도 이 분기를 실행
        } else if (updateProfile) { // 기본정보만 수정한 경우 실행
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
            Long taxInputId,
            TaxServiceRequest request,
            boolean deleteAll
    ) {
        taxValidator.validateDeleteRequest(taxInputId, request, deleteAll);

        Long transactionId = request.getTransactionId();
        List<Long> requestedIds = request.getTransactionIds();

        // 삭제 전에 분석 접근 권한을 확인하고 분석을 잠근다.
        requireInput(principal, taxInputId, true);

        if (deleteAll) {
            taxValidator.validateDeleteInputResult(taxMapper.deleteInput(taxInputId));
            return;
        }

        // 목록 삭제는 일부 거래가 이미 삭제된 경우에도 처리할 수 있다.
        if (requestedIds != null) {
            int deleted = taxMapper.deleteTransactionBatch(taxInputId, Set.copyOf(requestedIds));

            if (deleted > 0) {
                invalidateAfterTransactionChange(taxInputId);
            }

            return;
        }

        requireTransaction(taxInputId, transactionId);
        taxValidator.validateDeleteTransactionResult(taxMapper.deleteTransaction(taxInputId, transactionId));

        invalidateAfterTransactionChange(taxInputId);
    }

    /**
     * PDF에서 거래 내역을 추출한다.
     * 추출한 거래의 DB 저장은 save에서 처리한다.
     */
    @Override
    public PdfParseResponseDto parse(
            CustomUserDetails principal,
            Long taxInputId,
            MultipartFile file
    ) throws IOException {
        if (taxInputId != null) {
            requireInput(principal, taxInputId, false);
        }

        try {
            byte[] bytes = taxValidator.validateParseFile(file);

            // Python에서 PDF 분석과 OCR을 함께 수행하도록 원본을 전달한다.
            PdfParseResponseDto response = pythonPdfClient.analyze(
                    bytes,
                    file.getOriginalFilename()
            );

            taxValidator.validateParseResponse(response);

            List<TaxTransactionDto> transactions = preparePdfTransactions(response.getTransactions());

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

    private List<TaxTransactionDto> preparePdfTransactions(List<TaxTransactionDto> sources) {
        List<TaxTransactionDto> result = new ArrayList<>();

        for (TaxTransactionDto source : sources) {
            result.add(preparePdfTransaction(source));
        }

        return result;
    }

    private TaxTransactionDto preparePdfTransaction(TaxTransactionDto source) {
        taxValidator.validateParseTransactionPresent(source);

        // PDF 추출 거래는 아직 DB에 저장되지 않았으므로 식별자를 비운다.
        source.setTaxInputId(null);
        source.setTransactionId(null);

        TaxTransactionDto row;
        try {
            row = copyStoredTransaction(source, null);
            taxValidator.validateParseTransaction(source, row);
        } catch (ApiException exception) {
            return markPdfReview(source, exception.getMessage(), "거래 내용을 확인해주세요.");
        }

        if (taxValidator.requiresPdfReview(source)) {
            return markPdfReview(source, source.getFailureReason(), "추출한 거래 내용을 확인해주세요.");
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
        return row;
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

    private void applyCategoryChanges(Long taxInputId, List<TaxCategoryChange> changes) {
        Set<Long> seenTransactionIds = new HashSet<>();
        List<TaxCategoryChange.Feedback> feedback = new ArrayList<>();

        for (TaxCategoryChange change : changes) {
            taxValidator.validateCategoryChangeTarget(change, seenTransactionIds);

            TaxTransactionDto row = requireTransaction(taxInputId, change.transactionId());
            taxValidator.validateCategory(change.category(), row);

            feedback.add(new TaxCategoryChange.Feedback(
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

        invalidateAfterTransactionChange(taxInputId);

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
    }

    private TaxTransactionDto requireTransaction(Long taxInputId, Long transactionId) {
        List<TaxTransactionDto> rows = taxMapper.findTransactions(taxInputId, transactionId);

        taxValidator.validateTransactionFound(rows);
        return rows.get(0);
    }

    private void invalidateAfterTransactionChange(Long taxInputId) {
        // 거래 변경 후 이전 계산 결과를 무효화하고 분석 수정 시각을 갱신한다.
        taxMapper.deleteResult(taxInputId);
        taxMapper.touchInput(taxInputId);
    }

    private String failureCode(Exception exception) {
        if (exception instanceof ApiException apiException) {
            return apiException.getCode();
        }

        return exception instanceof IOException ? "PDF_IO_ERROR" : "PDF_PROCESSING_FAILED";
    }
}
