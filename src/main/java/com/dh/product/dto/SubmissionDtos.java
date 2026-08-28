package com.dh.product.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotNull;

public class SubmissionDtos {

    public record SubmitRequest(@NotNull Long productId) {
    }

    /** 202 응답 본문. 결과는 {@code GET /api/submissions/{id}} 로 조회한다. */
    public record SubmitAcceptedResponse(Long submissionId, String status) {
    }

    public record ReviewRequest(String reviewNote) {
    }

    public record SubmissionIssueResponse(
            Long id,
            String code,
            String field,
            String message,
            String severity) {
    }

    public record SubmissionResponse(
            Long id,
            Long productId,
            String productName,
            Long sellerId,
            String sellerName,
            String status,
            String submittedBy,
            String reviewedBy,
            String reviewNote,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            List<SubmissionIssueResponse> issues) {
    }

    public record SubmissionSummaryResponse(
            Long id,
            Long productId,
            String productName,
            String status,
            LocalDateTime updatedAt) {
    }

    public record RequiredAttributeResponse(String code, String label, boolean required) {
    }

    /** admin.front 가 카테고리 선택 직후 동적 폼을 그리기 위해 조회한다. */
    public record CategoryRequirementResponse(
            Long categoryId,
            List<RequiredAttributeResponse> requiredAttributes,
            List<String> requiredDocuments,
            BigDecimal commissionRate,
            boolean restricted) {
    }

    public record ProductAttributeUpsertRequest(
            @NotNull List<ProductAttributeValue> attributes) {
    }

    public record ProductAttributeValue(@NotNull String code, String value) {
    }

    public record ProductAttributeResponse(String code, String value) {
    }
}
