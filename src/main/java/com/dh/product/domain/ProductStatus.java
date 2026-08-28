package com.dh.product.domain;

/**
 * 상품 노출 스위치. 판매자 상태머신(SellerStatus)과 달리 전이 검증이 없는 단순 값이다 -
 * 관리자가 언제든 임의로 바꿀 수 있다. 기존에는 "삭제"만 판매 중지 수단이었는데, 삭제는
 * 재고 이력까지 지워버려 감사 추적이 끊긴다(product.api#52 조사 중 발견) - PAUSED/ARCHIVED로
 * 대체 가능해진다.
 */
public enum ProductStatus {
    DRAFT,
    LIVE,
    PAUSED,
    ARCHIVED
}
