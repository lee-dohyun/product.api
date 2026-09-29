package com.dh.product.domain;

/** 배송비 정책 — 무료/조건부 무료/유료 (Glossary §8-2, product.api#79). */
public enum ShippingFeeType {
    FREE,
    CONDITIONAL,
    PAID
}
