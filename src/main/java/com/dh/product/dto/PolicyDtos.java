package com.dh.product.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** 상품 판매 정책 입출력(product.api#79). 필드 뜻은 gateway Wiki Glossary §8-2. */
public class PolicyDtos {

    /**
     * 전체 교체(PUT). 빠진 필드는 "비움"이다 — 상품 수정(PartnerProductRequest)과 같은 규칙.
     * enum 값은 문자열로 받아 서비스가 검사한다(모르는 값 → 400).
     */
    public record ProductPolicyRequest(
            String taxType,
            String kcCertType,
            @Size(max = 100) String kcCertNumber,
            String shippingFeeType,
            @DecimalMin("0") BigDecimal shippingFee,
            @DecimalMin("0") BigDecimal freeShippingThreshold,
            @Min(0) @Max(60) Short shippingLeadDays,
            @DecimalMin("0") BigDecimal jejuExtraFee,
            @DecimalMin("0") BigDecimal islandExtraFee,
            @DecimalMin("0") BigDecimal returnShippingFee,
            @DecimalMin("0") BigDecimal exchangeShippingFee,
            @Size(max = 300) String returnAddress,
            LocalDateTime saleStartAt,
            LocalDateTime saleEndAt,
            @Min(1) Integer maxPurchaseQuantity) {
    }

    public record ProductPolicyResponse(
            Long productId,
            String taxType,
            String kcCertType,
            String kcCertNumber,
            String shippingFeeType,
            BigDecimal shippingFee,
            BigDecimal freeShippingThreshold,
            Short shippingLeadDays,
            BigDecimal jejuExtraFee,
            BigDecimal islandExtraFee,
            BigDecimal returnShippingFee,
            BigDecimal exchangeShippingFee,
            String returnAddress,
            LocalDateTime saleStartAt,
            LocalDateTime saleEndAt,
            Integer maxPurchaseQuantity) {
    }
}
