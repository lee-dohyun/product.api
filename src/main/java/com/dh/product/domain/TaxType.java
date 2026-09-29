package com.dh.product.domain;

/** 부가세 과세 구분 — 과세/면세/영세 (Glossary §8-2, product.api#79). */
public enum TaxType {
    TAXABLE,
    TAX_FREE,
    ZERO_RATED
}
