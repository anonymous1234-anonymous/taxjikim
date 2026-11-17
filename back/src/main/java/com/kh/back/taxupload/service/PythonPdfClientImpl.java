package com.kh.back.taxupload.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.kh.back.common.exception.ApiException;
import com.kh.back.taxupload.dto.PdfParseResponseDto;
import com.kh.back.taxupload.dto.TaxCategoryChange;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class PythonPdfClientImpl implements PythonPdfClient {

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public PythonPdfClientImpl(
            @Value("${python.api-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${python.connect-timeout-ms:5000}") int connectTimeout,
            @Value("${python.read-timeout-ms:90000}") int readTimeout
    ) {
        if (connectTimeout <= 0 || readTimeout <= 0) {
            throw new IllegalArgumentException("Python 통신 제한 시간은 양수여야 합니다.");
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory(); // http 요청을 만드는 객체를 생성
        factory.setConnectTimeout(connectTimeout); // 서버와 연결될 때까지 기다리는 최대시간을 설정
        factory.setReadTimeout(readTimeout); // 연결된 뒤 서버의 응답 데이터를 읽을떄 기다리는 최대시간을 설정

        this.client = RestClient.builder() /// 클라이언트 설정시작
                .baseUrl(baseUrl) // 서버의 기본 주소를 정하고
                .requestFactory(factory) // 위의 시간 제한 설정을 적용한뒤
                .build(); // build로 완성한 클라이언트를 this.client 에 저장

        this.objectMapper = createObjectMapper();
    } // json과 자바 객체를 서로 변환하는 도구를 만들어  this.objectMapper 에 저장


    @Override
    public void sendTrainingFeedback(List<TaxCategoryChange.Feedback> feedback) { // 피드백 목록을 외부 서버의 /api/training-feedback 에 json으로 보내는 메서드
        client.post()
                .uri("/api/training-feedback")
                .contentType(MediaType.APPLICATION_JSON) // 요청 본문의 형식을 json으로 지정하고
                .body(feedback) // / feedback 목록을 본문에 담음
                .retrieve() // 요청을 보내고 , 서버 응답의 본문은 읽지 않음
                .toBodilessEntity(); // 기본동작에서는 서버가 4xx나 5xx 를 반환하면 예외가 발생
    }

    private ObjectMapper createObjectMapper() { // 이 클래스 내부에서만 호출가능하고, json 변환 도구인 objectmapper를 반환하는 메서드
        SimpleModule dateModule = new SimpleModule(); // 날짜를 json 으로 쓰거나 json에서 읽는 방식을 등록할 모듈을 만듬 , 이 줄에서는 빈 모듈을 생성

        dateModule.addDeserializer(
                LocalDate.class, // 값을 읽을떄 이규칙을 사용하도록 등록 , deserialize (...) 는 날짜 값을 읽을 때 실행 됨 , parser로 현재 json 값의 타입과 내용을 확인할수있음
                new JsonDeserializer<LocalDate>() {
                    @Override
                    public LocalDate deserialize(
                            JsonParser parser,
                            DeserializationContext context
                    ) throws IOException {
                        if (!parser.hasToken(JsonToken.VALUE_STRING)) { // 만약 현재 값이 문자열이 아니라면 , ex: 2026-11-16은 이 검사를 통과하지만 , 숫자 20261116은 예외 발생시킴
                            throw JsonMappingException.from(
                                    parser,
                                    "거래 날짜는 YYYY-MM-DD 문자열 또는 null이어야 합니다."
                            );
                        }

                        String value = parser.getText().trim(); // 날짜 문자열을 가져와 앞뒤 공백을 제거함

                        try {
                            return LocalDate.parse(value); // 2026-11-16 같은 문자열을 날짜 객체로 바꿔 반환
                        } catch (DateTimeParseException exception) { // 2026/11/16처럼 형식이 다르거나 2026-02-30 처럼 존재하지 않는 날짜라면 catch 예외 발생
                            throw JsonMappingException.from(
                                    parser,
                                    "거래 날짜의 형식이 올바르지 않습니다."
                            );
                        }
                    }
                }
        );

        ObjectMapper mapper = new ObjectMapper(); // json변환 도구 생성
        mapper.registerModule(dateModule); // 앞에서 만든 localdate 변환 규칙을 등록

        // 보조 필드는 허용하고 필수 응답 구조는 parseResponse에서 확인한다.
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES); // json에 자바객체가 모르느 추가 필드가 있어도 무시, 필수 응답구조는 parseresponse에서 따로 확인
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS); // Map<String, Object> 처럼 값의 타입이 정해지지 않은 곳에서 json 소수를 읽을떄 BigDecimal 을 사용해 소수 값을 정확하게 다룸
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS); // json 응답 하나를 읽은뒤 다른 값이 이어지면 오류를 냄, ex: {"ok":true } {"extra": 1} 은 거부

        return mapper;
    }

    @Override
    public PdfParseResponseDto analyze(byte[] pdfBytes, String fileName) {
        MultiValueMap<String, Object> parts = createParts(pdfBytes, fileName); // pdf 르 http 요청에 첨부할수 있도록 parts를 만듬
        String responseBody;

        try {
            responseBody = client.post()
                    .uri("/api/pdf/analyze")// 외부 서버의 pdf 분석 주소로 post 요청 준비
                    .contentType(MediaType.MULTIPART_FORM_DATA)// 파일 업로드형식으로 pdf를 요청 본문에 담음
                    .accept(MediaType.APPLICATION_JSON)// 서버에 json 응답을 요청
                    .body(parts)
                    .retrieve()
                    .body(String.class);// 요청을 보내고 응답 본문을 문자 열로 읽어 responseBody 에 저장

        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value(); // 응답의 http  상태코드를 숫자로 꺼내 status 에 저장
            log.warn("Python PDF 서버 HTTP 오류. status={}", status);

            if (status == 400 || status == 422) { // 서버가 400, 422, 400 같은 오류 응답을 보냈을때 발생한 예외를 잡음
                String code = null;

                try {
                    JsonNode root = objectMapper.readTree( // objectMapper.readTree(...)가 json을 json Node 로 변환해 root 에 담으면 이후 root에서 오류 코드 같은 필드를 꺼낼수 잇음
                            exception.getResponseBodyAsString()
                    );

                    if (root != null) { // 읽어온 json 이 있을때만 아래줄을 실행한다
                        code = root.path("detail").path("code").asText(null); //detail 안의 code 를 문자열로 가져옴 , ex : {"detail": {"code":INVALID_PDF"}} 이면 code 에 invalid_pdf 가 들어가고 해당 필드가 없으면 null이 들어감
                    }
                } catch (JsonProcessingException ignored) { // 앞의 try에서 오류 응답을 json 으로 읽다가 실패하면 그 예외 발생
                    // ignored 는 예외 객체를 사용하지 않겠다는 의도로 붙인 변수 이름임
                }

                if ("PDF_DAILY_DETAILS_REQUIRED".equals(code)) {
                    throw new ApiException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            code,
                            "날짜별 상세 내역을 업로드해주세요. "
                                    + "월별·지출처별 합계는 일별 거래로 저장하지 않습니다."
                    );
                } // 외부 pdf 서버에서 받은 오류 코드가 pdf_daily_details_required이면 날짜별 상세내역이 필요하다 는 안내와 함께 http 422 오류 반환

                if ("PDF_TRANSACTIONS_NOT_FOUND".equals(code)) { // 받은 오류코드가 pdf_transactions_not_found 와 같은지 확인
                    throw new ApiException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            code,
                            "날짜별 거래 내역을 찾지 못했습니다. "
                                    + "상세 내역 또는 OCR 인식 결과를 확인해주세요."
                    );
                }

                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "PDF_ANALYSIS_INPUT_INVALID",
                        "PDF 파일 형식을 확인해주세요."
                );
            }

            if (status == 503) {
                throw new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "PDF_ANALYSIS_UNAVAILABLE",
                        "PDF 분석 서버에서 현재 분석을 처리할 수 없습니다."
                );
            }

            if (status == 413) {
                throw new ApiException(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "PDF_TOO_LARGE",
                        "PDF 파일 크기가 허용 범위를 초과했습니다."
                );
            }

            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "PDF_ANALYSIS_FAILED",
                    "PDF 분석 서버 처리에 실패했습니다."
            );

        } catch (ResourceAccessException exception) {
            boolean timeout = hasTimeoutCause(exception);
            log.warn("Python PDF 서버 통신 실패. timeout={}", timeout);

            if (timeout) {
                throw new ApiException(
                        HttpStatus.GATEWAY_TIMEOUT,
                        "PDF_ANALYSIS_TIMEOUT",
                        "PDF 분석 서버의 응답 시간이 초과되었습니다."
                );
            }

            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PDF_ANALYSIS_UNAVAILABLE",
                    "PDF 분석 서버와 통신할 수 없습니다."
            );

        } catch (RestClientException exception) {
            log.error(
                    "Python PDF 응답 수신 실패. exceptionType={}",
                    exception.getClass().getName()
            );

            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "PDF_RESPONSE_ERROR",
                    "PDF 분석 응답을 수신하지 못했습니다."
            );
        }

        return parseResponse(responseBody);
    }

    private PdfParseResponseDto parseResponse(String responseBody) { //pdf 분석 서버의 응답 문자열을 받아 pdfParseResponseDto로 변환하는 클래스 내부 메서드
        if (responseBody == null || responseBody.isBlank()) { // responseBody가 null 이거나, 빈 문자열 또는 공백뿐이면 apiexception을 던짐
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "PDF_EMPTY_RESPONSE",
                    "PDF 분석 서버가 빈 응답을 반환했습니다."
            );
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody); // 응답 문자열을 json 트리로 변환해 root에 담음

            // Python API는 data로 감싸지 않은 분석 결과 객체를 반환한다.
            if (root == null || !root.isObject()) { // 읽은 값이 없거나 최상위 값이 {...} 형태의 json 객체가 아니면 오류를 던짐
                throw invalidResponse("최상위 응답이 JSON 객체가 아닙니다.");
            }

            if (!root.path("transactions").isArray()) { // 최상위 객체에 transactions 필드가 없거나 값이 배열 [...] 이 아니면 오류 던짐
                throw invalidResponse("transactions 배열이 없습니다.");
            }

            for (String field : List.of( // pdf 분석 응답의 totalpages,extractedcount,parsedcount , failedcount 를 하나씩 꺼내 0 이상이고 자바 int 범위에 들어가는 정수인지 검사하는 코드
                    "totalPages",
                    "extractedCount",
                    "parsedCount",
                    "failedCount"
            )) {
                JsonNode value = root.get(field); // root.get(field)로 값을 가져온뒤 필드가 없거나 값이 정수가 아니거나 int 범위를 벗어나거나 음수이면
                //  해당 필드 이름을 담아 오류를 던짐  || 는 조건중 하나라도 참이면 오류로 처리한다
                // ex: 3과 0은 통과하고 "3"은 문자열이라서, 3.0은 소수형식이라서 -1인 음수라서 오류가 남
                if (value == null
                        || !value.isIntegralNumber()
                        || !value.canConvertToInt()
                        || value.intValue() < 0) {
                    throw invalidResponse(field + " 값이 올바른 정수가 아닙니다.");
                }
            }

            PdfParseResponseDto response = objectMapper.treeToValue(
                    root,
                    PdfParseResponseDto.class // treeToValue(...)는 앞에서 검사한 json 트리 root 를  pdfParseResponseDto 타입의 자바 객체로 변환하고 그 결과를 response 변수에 담음
            );  // pdfparseresponsedto.class 는 어떤 타입으로 변환할지 지정

            if (response.getTransactions().stream().anyMatch(Objects::isNull)) { // 거래 목록에 null인 항목이 하나라도 있는지 확인, ex: [null] 이면 true 가 되어 잘못된 응답으로 처리하고 예외를 던짐
                throw invalidResponse("transactions 배열에 null 항목이 있습니다.");
            }

            log.info(
                    "Python PDF 응답 변환 완료. pages={}, transactions={}, parsed={}",
                    response.getTotalPages(),
                    response.getTransactions().size(),
                    response.getParsedCount()
            );


            response.setPersisted(false); // 저장여부를 false 로 ,
            response.setSavedCount(0); // 저장 건수를 0으로 설정
            response.setTaxInputIdsByYear(null); // 저장된 연도별 분석 id가 없음을 표시

            return response; // 이렇게 설정한 분석결과를 돌려줌

        } catch (JsonMappingException exception) { // json 을 자바 객체로 반환하다 필드 형식이 맞지 않는 등의 문제가 생기면 실행

            log.error( // 거래 값이 로그에 남지 않도록 예외 종류와 문제가 발생한 필드 경로만 기록, http 502 와 pdf_response_json_error 를 반환하도록 예외 던짐
                    "Python PDF DTO 변환 실패. exceptionType={}, fieldPath={}",
                    exception.getClass().getName(),
                    mappingPath(exception)
            );

            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "PDF_RESPONSE_MAPPING_ERROR",
                    "PDF 분석 응답의 필드 형식을 확인해주세요."
            );

        } catch (JsonProcessingException exception) {
            log.error(
                    "Python PDF JSON 해석 실패. exceptionType={}",
                    exception.getClass().getName()
            );

            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "PDF_RESPONSE_JSON_ERROR",
                    "PDF 분석 서버가 올바른 JSON을 반환하지 않았습니다."
            );
        }
    }

    private ApiException invalidResponse(String reason) { // pdf 분석 서버의 응답구조가 예상과 다를떄 사용할 예외를 만드는 메서드
        log.error("Python PDF 응답 구조 오류. reason={}", reason);

        return new ApiException(
                HttpStatus.BAD_GATEWAY,
                "PDF_RESPONSE_STRUCTURE_ERROR",
                "PDF 분석 응답 구조가 예상 형식과 다릅니다."
        );
    }

    private String mappingPath(JsonMappingException exception) { // json을 자바 객체로 변환하다 오류가 난 위치를 로그에 남길 문자열로 만드는 메서드
        StringBuilder path = new StringBuilder();

        for (JsonMappingException.Reference reference : exception.getPath()) {
            if (reference.getFieldName() != null) {
                if (!path.isEmpty()) {
                    path.append(".");
                }

                path.append(reference.getFieldName());

            } else if (reference.getIndex() >= 0) {
                path.append("[")
                        .append(reference.getIndex())
                        .append("]");
            }
        }

        return path.isEmpty() ? "(경로 확인 불가)" : path.toString();
    }

    private MultiValueMap<String, Object> createParts(
            byte[] pdfBytes,
            String fileName
    ) {
        ByteArrayResource resource = new ByteArrayResource(pdfBytes) { // pdf 의 바이트 배열을 스프링이 파일 처럼 읽을 수 있는 객체로 감쌈
            @Override
            public String getFilename() {
                return fileName;
            } // getFilename() 을 재 정의해 요청에 첨부할 파일 이름으로 fileName을 사용
        };


        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>(); // parts라는 요청 데이터 묶음을 만들고 , file 이라는 이름으로 pdf 파일(resource)을 담은뒤 그 묶음을 반환
        parts.add("file", resource); // multivaluemap 은 같은 이름에 여러 값을 담을 수 있지만 여기서는 file에 pdf 하나만 담음

        return parts;
    }

    private boolean hasTimeoutCause(Throwable exception) {
        Throwable current = exception;

        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }
}