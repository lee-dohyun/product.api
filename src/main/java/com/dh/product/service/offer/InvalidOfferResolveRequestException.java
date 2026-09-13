package com.dh.product.service.offer;

/**
 * {@code /internal/offers/resolve} 요청 파라미터가 잘못됐을 때 - {@code ids} 와 {@code variantIds}
 * 를 둘 다 줬거나 둘 다 안 줬을 때(product.api#69).
 *
 * <p>호출자가 고칠 수 있는 요청 오류라 400 으로 매핑한다. 범용 {@link IllegalArgumentException} 에
 * 걸면 무관한 엔드포인트의 응답 코드가 같이 바뀌므로 전용 예외로 둔다 -
 * {@code CategoryHierarchyException} 과 같은 판단이다.
 */
public class InvalidOfferResolveRequestException extends RuntimeException {

    public InvalidOfferResolveRequestException(String message) {
        super(message);
    }
}
