package com.dh.product.service;

/**
 * 부분 복원을 받을 수 없다(gateway#320) — 이 주문이 그 재고에서 뺀 수량을 넘기거나, 차감이 없거나, 이미 주문 전체가
 * 복원됐다. 호출자(order.api)의 요청 오류이므로 {@link IllegalStateException} 핸들러가 409 로 응답한다.
 * 재고를 늘리는 쪽 실패라 조용히 성공으로 바꾸면 없던 재고가 생긴다.
 */
public class PartialRestoreRejectedException extends IllegalStateException {

    public PartialRestoreRejectedException(String message) {
        super(message);
    }
}
