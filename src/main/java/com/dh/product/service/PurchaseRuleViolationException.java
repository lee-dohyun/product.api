package com.dh.product.service;

/**
 * 판매 정책상 지금 살 수 없음(product.api#97) — 판매 기간 밖, 1회 최대 구매 수량 초과.
 * IllegalStateException 을 상속해 ApiExceptionHandler 가 409 + 사유 문구로 응답한다(상태 충돌).
 */
public class PurchaseRuleViolationException extends IllegalStateException {
    public PurchaseRuleViolationException(String message) {
        super(message);
    }
}
