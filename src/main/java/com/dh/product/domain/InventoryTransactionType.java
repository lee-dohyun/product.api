package com.dh.product.domain;

public enum InventoryTransactionType {
    RESTOCK,
    ADJUSTMENT,
    ORDER_DEDUCT,
    ORDER_RESTORE,
    /**
     * 교환 발송분 차감(gateway#320). 주문 차감(ORDER_DEDUCT)과 따로 둔다 — 주문 차감의 멱등(주문당 되돌리지 않은 차감 1건,
     * V24 유니크)과 주문 전체 복원의 대상에서 빠져야 하기 때문이다. 멱등 키는 claim_key(V25 유니크).
     */
    EXCHANGE_DEDUCT
}
