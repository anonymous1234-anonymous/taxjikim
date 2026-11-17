package com.kh.back.taxupload.mapper;
import com.kh.back.taxupload.dto.TaxCategoryChange;
import com.kh.back.taxupload.dto.TaxTransactionDto;
import com.kh.back.taxupload.dto.TaxTransactionDto.TaxInputRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;

@Mapper
public interface TaxMapper {

    int insertInput(TaxInputRequest input); // input 에 담긴 연말 정산 분석 기본정보를 db에 저장하고 삽입된 행수를 반환, 한건을 저장하면 보통 1 임

    TaxInputRequest findInput(
            @Param("taxInputId") Long taxInputId // taxinputid로 db에서 해당 분석의 기본정보를 조회해 taxinputrequest 객체로 반환
    );

    TaxInputRequest lockInput(
            @Param("taxInputId") Long taxInputId // taxinputid로 연말정산 분석 기본정보를 조회하면서 해당 db행을 잠그고 조회결과를 taxinputRequest 로 반환
    );                                           // 잠금은 현재 트랜잭션이 끝날때까지 다른 트랜잭션이 같은 행을 수정하는 것을 막음

    int updateInput(TaxInputRequest input); // input에 담긴 정보로 db의 기존 연말정산 분석 기본정보를 수정하고 수정된 행의 개수를 int로 반환한다

    int deleteInput(
            @Param("taxInputId") Long taxInputId // taxinput id에 해당하는 여말정산 분석 기본정보를 db에서 삭제하고 , 삭제한 행의 개수를 int로 반환한다
    );

    int touchInput(
            @Param("taxInputId") Long taxInputId // taxinputid에 해당하는 연말정산 분석의 마지막 수정시각을 현재시각으로 갱신하고 갱신한 행의 갯수를 int로 반환
    );

    int insertTransactions(@Param("rows") List<TaxTransactionDto> rows); // rows에 담긴 연말정산 거래목록을 db에 저장하고 삽입한 행의 개수를 int 로 반환한다


    List<TaxTransactionDto> findTransactions(
            @Param("taxInputId") Long taxInputId,
            @Param("transactionId") Long transactionId
    ); // taxinputid 에 해당하는 연말정산 분석의 거래 내역을 db에서 조회해 List<TransactionDto> 로 반환 , transactionId 를 넘기면 해당 거래만 조회하고 null 을 넘기면 해당 분석의 전체 거래를 조회한다

    int updateCategories(
            @Param("taxInputId") Long taxInputId,
            @Param("changes") List<TaxCategoryChange> changes // taxinputId에 해당하는 연말정산 분석에서 changes 목록에 담긴 변경사항대로 거래의 분류를 수정하고 수정된 db행의 개수를 int로 반환
    );

    int deleteTransactionBatch(@Param("taxInputId") Long taxInputId,
                               @Param("transactionIds") Set<Long> transactionIds); // taxinputid에 해당하는 연말정산 분석에서 transactionids 에 포함된 거래를 삭제하고 실제로 삭제한 db해의 수를 int로 반환
    // 사용자가 여러거래를 선택해 삭제할떄, 거래마다 sql을 실행하는 대신 in 조건을 사용하여 delete 쿼리 한번으로 처리가능 , taxinputid 는 삭제 범위를 해당 분석ㅇ로 제한하고 set<Long>은 중복 거래 id를 하나로 모음
    int deleteTransaction(
            @Param("taxInputId") Long taxInputId,
            @Param("transactionId") Long transactionId
    ); // taxinputid에 해당하는 연말정산 분석에서 transactionId로 지정한 거래 한건을 삭제함 , 반환값 int는 삭제된 db행수로 , 삭제되면 1, 두 id 에 모두 일치하는 거래가 없으면 0

    int deleteResult(
            @Param("taxInputId") Long taxInputId
    ); // taxinputid에 해당하는 연말정산 분석결과를 db에서 삭제하고 실제로 삭제한 행의 수를 int로 반환

}