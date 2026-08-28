package com.dh.product.service.submission;

/**
 * 제출 검증 실행을 의뢰하는 지점. 지금 구현({@link InlineSubmissionValidationPublisher})은
 * 같은 요청 안에서 바로 실행하지만, 인터페이스로 감싸 둔 이유는 Redis/Kafka/SQS 큐로
 * 옮길 때 이 타입의 구현만 갈아끼우면 되게 하기 위함이다(product.api#30).
 *
 * <p>호출부(API)는 이미 비동기 계약이다 - {@code POST /api/submissions} 가 202 + id 를
 * 돌려주고 결과는 {@code GET /api/submissions/{id}} 로 조회한다. 그래서 실행이 동기에서
 * 비동기로 바뀌어도 클라이언트 계약은 그대로다.
 */
public interface SubmissionValidationPublisher {

    void publish(Long submissionId);
}
