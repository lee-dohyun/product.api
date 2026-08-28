package com.dh.product.service.submission;

import com.dh.product.domain.IssueSeverity;

/**
 * 검증 규칙 하나가 찾아낸 문제. 아직 DB 에 저장되기 전 형태다 -
 * {@link com.dh.product.domain.SubmissionIssue} 로 옮겨 담아 영속화한다.
 */
public record ValidationFinding(String code, String field, String message, IssueSeverity severity) {

    public static ValidationFinding blocking(String code, String field, String message) {
        return new ValidationFinding(code, field, message, IssueSeverity.BLOCKING);
    }

    public static ValidationFinding warning(String code, String field, String message) {
        return new ValidationFinding(code, field, message, IssueSeverity.WARNING);
    }
}
