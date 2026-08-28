package com.dh.product.domain;

/**
 * 입점 심사 상태머신(product.api#29). boolean approved 하나로 만들지 않은 이유는
 * "승인은 됐지만 판매 설정 미완료"나 "이 카테고리는 권한 없음" 같은 중간 상태를
 * 표현할 자리가 필요하기 때문이다(조사한 5개 몰 공통 패턴).
 *
 * <pre>
 * DRAFT → SUBMITTED → IN_REVIEW → ACTIVE ⇄ SUSPENDED → TERMINATED
 *             ↑            ↓
 *       (보완 재제출)   REJECTED
 * </pre>
 */
public enum SellerStatus {
    DRAFT,
    SUBMITTED,
    IN_REVIEW,
    ACTIVE,
    SUSPENDED,
    TERMINATED,
    REJECTED
}
