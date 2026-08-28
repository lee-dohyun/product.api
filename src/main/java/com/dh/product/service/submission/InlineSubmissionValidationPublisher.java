package com.dh.product.service.submission;

import org.springframework.stereotype.Component;

/**
 * 기본 구현 - 큐 없이 같은 스레드에서 바로 검증을 돌린다.
 *
 * <p>카탈로그 규모가 데모 수준이라 큐를 먼저 세울 이유가 없다(~/msa/AGENTS.md §2 "무거운
 * 인프라는 도입하지 말고 기록만 한다"). 발행 지점이 인터페이스로 분리돼 있으므로 Redis/Kafka 로
 * 옮길 때 이 클래스만 교체하면 되고, API 계약(202 + id, 결과는 조회)은 이미 비동기라
 * 클라이언트는 바뀌지 않는다.
 */
@Component
public class InlineSubmissionValidationPublisher implements SubmissionValidationPublisher {

    private final ProductSubmissionService submissionService;

    public InlineSubmissionValidationPublisher(ProductSubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @Override
    public void publish(Long submissionId) {
        submissionService.runValidation(submissionId);
    }
}
