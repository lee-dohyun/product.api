package com.dh.product.domain;

public enum SellerType {
    /** 자사. id=1 하나뿐이며, order_items.seller_id=1(order.api#13)과 짝이 맞아야 한다. */
    FIRST_PARTY,
    /** 공급사. product.api#29가 다루는 실제 대상. */
    SUPPLIER,
    /** 3P 판매자. 오퍼 분리(product.api#31) 전까지는 미사용. */
    SELLER
}
