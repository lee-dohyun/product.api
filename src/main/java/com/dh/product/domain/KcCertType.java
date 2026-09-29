package com.dh.product.domain;

/** KC 인증 유형 — 대상 아님/안전인증/안전확인/공급자적합성확인 (Glossary §8-2, product.api#79). */
public enum KcCertType {
    NONE,
    SAFETY_CERT,
    SAFETY_CONFIRM,
    SUPPLIER_CONFORMITY
}
