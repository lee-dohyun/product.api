package com.dh.product.service.partner;

/**
 * 파트너 요청의 모양이 규칙에 어긋남(400) — 파트너가 고쳐서 다시 보낼 수 있는 입력 오류.
 * 범용 IllegalArgumentException 에 걸지 않는 이유는 CategoryHierarchyException 주석과 같다:
 * 무관한 엔드포인트의 응답 코드가 같이 바뀐다.
 */
public class InvalidPartnerRequestException extends RuntimeException {
    public InvalidPartnerRequestException(String message) {
        super(message);
    }
}
