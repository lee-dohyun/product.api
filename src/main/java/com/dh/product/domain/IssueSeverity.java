package com.dh.product.domain;

public enum IssueSeverity {
    /** 하나라도 있으면 제출이 NEEDS_FIX 로 떨어진다. */
    BLOCKING,
    /** 통과는 되지만 심사자에게 표시된다. */
    WARNING
}
