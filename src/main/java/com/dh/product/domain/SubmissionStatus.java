package com.dh.product.domain;

/**
 * 상품 검수 제출 상태머신(product.api#30).
 *
 * <pre>
 * DRAFT → SUBMITTED → VALIDATING → IN_REVIEW → LIVE ⇄ PAUSED
 *             ↑            ↓
 *          (재제출)     NEEDS_FIX
 * </pre>
 *
 * {@code VALIDATING}(규칙 검증)을 {@code IN_REVIEW}(사람 심사) <b>앞에</b> 둔 것이 핵심이다.
 * 규칙이 걸러낸 것만 사람 큐에 올라간다 - 순서를 반대로 두면 사람이 오탈자를 잡는 데 시간을 쓴다.
 */
public enum SubmissionStatus {
    DRAFT,
    SUBMITTED,
    VALIDATING,
    NEEDS_FIX,
    IN_REVIEW,
    LIVE,
    PAUSED
}
