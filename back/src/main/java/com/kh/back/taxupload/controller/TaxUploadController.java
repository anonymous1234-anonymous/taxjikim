package com.kh.back.taxupload.controller;

import com.kh.back.auth.security.CustomUserDetails;
import com.kh.back.common.dto.ApiResponse;
import com.kh.back.taxupload.dto.PdfParseResponseDto;
import com.kh.back.taxupload.dto.TaxServiceResponse;
import com.kh.back.taxupload.dto.TaxTransactionDto;
import com.kh.back.taxupload.service.YearEndTaxService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/tax")
@RequiredArgsConstructor
public class TaxUploadController {

    private final YearEndTaxService service;

    @PostMapping("/save") // post / api /v1/tax/save 요청이 오면 이 메서드를 실행
    public ResponseEntity<ApiResponse<TaxServiceResponse>> save( // 다른 클래스에서도 호출할수있는 save 메서드로 , 저장 결과를 공통 응답 형식에 담아 http 상태코드와 함께 반환
                                                                 @AuthenticationPrincipal CustomUserDetails principal, // 스프링이 현재 로그인한 사용자 정보를 principal에 넣어준다는 뜻
                                                                 @RequestBody TaxTransactionDto.TaxServiceRequest request // 스프링이 요청 본문의 json을 taxserviceRequest 객체로 반환해 request 에 넣어준다는 뜻
    ) {
        boolean createAnalysis = request.getTaxInputId() == null; // 요청에 분석 id 가 없으멵 새분석을 만든다는 의미로 createanalysis에 true 를 넣고 id가 있으면 false 를 넣음
        TaxServiceResponse saved = service.save( // 로그인한 사용자 정보 principal 와 요청 데이터 request 를 서비스에 넘겨 저장하고 그결과를 saved에 담음
                principal, request
        );
        HttpStatus status = createAnalysis ? HttpStatus.CREATED : HttpStatus.OK; // 새분석을 만들었으면 201 created , 기존 분석에 거래를 저장했으면 200 ok를 status 에 담음

        return ResponseEntity.status(status) // 방금 정한 상태 코드로 http 응담을 만듬
                .cacheControl(CacheControl.noStore()) // 브라우저나 중간 캐시에 이 응답을 저장하지 말라고 지정
                .body(ApiResponse.ok(saved)); // 저장 결과 saved 를 공통 성공 응답 형식으로 감싸 응답 본문에 담음
    }

    @PostMapping("/inputs/{taxInputId}/update") // 해당 주소로 post 요청이 오면 update 메서드 실행
    public ApiResponse<TaxTransactionDto.TaxInputRequest> update(
            @AuthenticationPrincipal CustomUserDetails principal, // 현재 로그인한 사용자 정보를 받음
            @PathVariable("taxInputId") Long taxInputId, // url 에서 수정할 분석 id 를 가져옴 , ex: /inputs/42/update 면 taxinputid는 42
            @RequestBody TaxTransactionDto.TaxServiceRequest request // 요청 본문의 json 을 수정 데이터로 받음
    ) {
        return ApiResponse.ok(
                service.update(principal, taxInputId, request) // service.update(principal, taxinputid, request ) 가 실제 수정을 처리하고 , api Response.ok(...)가 수정 결과를 공통 성공 응답 형식으로 감싸 반환
        );
    }

    @PostMapping("/inputs/{taxInputId}/delete") // 이 주소로 post 요청이 오면 delete 메서드를 실행
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal CustomUserDetails principal, // 현재 로그인한 사용자 정보를 받음
            @PathVariable("taxInputId") Long taxInputId, // url 에서 대상 분석 id를 가져옴 , /inputs/42/delete라면 42임
            @RequestParam(value = "deleteAll", defaultValue = "false") boolean deleteAll, // url에서 전체 삭제 여부를 읽음
            @RequestBody TaxTransactionDto.TaxServiceRequest request // 요청 본문의 json을 삭제 요청 데이터로 받음
    ) {
        service.delete(principal, taxInputId, request, deleteAll); // 사용자 정보 , 분석 id , 요청 데이터 , 전체 삭제 여부를 받아 실제 삭제를 처리
        return ResponseEntity.noContent().build(); // 삭제가 성공하면 responseEntity.nocontent().build()가 분문 없이 204 no content 를 반환 , responseentity<void>는 응답 본문이 없다는 뜻
    }

    @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) // consumes는 파일을 보낼때 사용하는 multipart/form-data 형식을 뜻함
    public ApiResponse<PdfParseResponseDto> parse(
            @AuthenticationPrincipal CustomUserDetails principal, // 현재 로그인한 사용자 정보가 들어감
            @RequestParam(value = "taxInputId", required = false) Long taxInputId, // taxinputid 는 기존 분석의 id 이며 required = false 라서 생략할수 있고 id를 보내면 해당 분석에 접근할 권한을 확인
            @RequestParam("file") MultipartFile file // multipartfile file은 요청에서 file이라는 이름으로 보낸 pdf 파일임
    ) throws IOException {
        // 기존 분석을 참조하는 PDF 요청은 해당 분석의 접근 권한을 확인한다.
        return ApiResponse.ok(
                service.parse(principal, taxInputId, file) // service.parse(...) 가 pdf를 처리하고 apiResponse.ok(...)가 추출결과 (pdfParseResponseDto)를 공통 성공 응답 형식으로 감싸 반환
        );
    }
}