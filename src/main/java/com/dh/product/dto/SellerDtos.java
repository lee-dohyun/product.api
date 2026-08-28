package com.dh.product.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class SellerDtos {

    public record SellerResponse(
            Long id,
            String name,
            String businessRegistrationNo,
            String mailOrderSalesNo,
            String representativeName,
            String address,
            String phone,
            String email,
            String status,
            String type,
            String settlementBank,
            String settlementAccount,
            String shippingOriginAddress,
            String returnAddress,
            String shippingFeePolicy,
            String csContact,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    public record SellerSummaryResponse(
            Long id,
            String name,
            String status,
            String type,
            LocalDateTime createdAt) {
    }

    /** 공급사 신규 등록. type은 항상 SUPPLIER로 고정한다 - FIRST_PARTY는 시딩 1건뿐, SELLER(3P)는 아직 미사용. */
    public record SellerCreateRequest(
            @NotBlank String name,
            @NotBlank String businessRegistrationNo,
            String mailOrderSalesNo,
            @NotBlank String representativeName,
            @NotBlank String address,
            @NotBlank String phone,
            @NotBlank String email) {
    }

    /** 기본 정보 + 판매 설정을 함께 수정한다 - 심사 상태 전이는 별도 엔드포인트(SellerTransitionRequest). */
    public record SellerUpdateRequest(
            @NotBlank String name,
            @NotBlank String businessRegistrationNo,
            String mailOrderSalesNo,
            @NotBlank String representativeName,
            @NotBlank String address,
            @NotBlank String phone,
            @NotBlank String email,
            String settlementBank,
            String settlementAccount,
            String shippingOriginAddress,
            String returnAddress,
            String shippingFeePolicy,
            String csContact) {
    }

    /**
     * 상태 전이 요청. toStatus만 받고 changedBy는 컨트롤러가 인증된 관리자 이메일로 채운다 -
     * 요청 본문으로 받으면 위조 가능하다.
     */
    public record SellerTransitionRequest(
            @NotNull String toStatus,
            String reasonCode,
            String reasonNote) {
    }

    public record SellerStatusHistoryResponse(
            Long id,
            String fromStatus,
            String toStatus,
            String reasonCode,
            String reasonNote,
            String changedBy,
            LocalDateTime createdAt) {
    }

    public record SellerDocumentCreateRequest(
            @NotBlank String docType,
            @NotBlank String fileKey,
            LocalDate issuedAt) {
    }

    public record SellerDocumentResponse(
            Long id,
            String docType,
            String fileKey,
            LocalDate issuedAt,
            LocalDateTime verifiedAt,
            String rejectReason,
            LocalDateTime createdAt) {
    }

    public record SellerDetailResponse(
            SellerResponse seller,
            List<SellerDocumentResponse> documents,
            List<SellerStatusHistoryResponse> history) {
    }
}
