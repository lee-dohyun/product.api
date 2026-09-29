package com.dh.product.service;

/** 판매 정책 입력이 규칙에 어긋남(400) — 고쳐서 다시 보낼 수 있는 입력 오류(product.api#79). */
public class InvalidProductPolicyException extends RuntimeException {
    public InvalidProductPolicyException(String message) {
        super(message);
    }
}
