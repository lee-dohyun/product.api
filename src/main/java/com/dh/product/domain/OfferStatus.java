package com.dh.product.domain;

public enum OfferStatus {
    /** 판매 중. 대표 오퍼 후보가 되는 유일한 상태다. */
    ACTIVE,
    /** 일시 중지. 판매자가 잠시 내린 것 - 되살릴 수 있다. */
    PAUSED,
    /** 종료. 다시 팔려면 새 오퍼를 만든다. */
    ENDED
}
