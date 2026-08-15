package com.dh.product.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class ProductDtos {

    public record CategoryResponse(Long id, String name, Long parentId) {
    }

    public record ProductImageResponse(Long id, String imageUrl, Short sortOrder) {
    }

    public record VariantOptionValueResponse(Long optionId, String optionName, Long valueId, String value) {
    }

    public record VariantResponse(
            Long id,
            String sku,
            BigDecimal price,
            boolean active,
            Integer stockQuantity,
            List<VariantOptionValueResponse> optionValues) implements Serializable {
    }

    public record OptionValueResponse(Long id, String value) {
    }

    public record OptionResponse(Long id, String name, List<OptionValueResponse> values) {
    }

    // 단일 상품 조회 응답 - Redis에 JSON으로 캐싱되므로 Serializable
    // price/stockQuantity는 저장된 값이 아니라 활성 variant로부터 매번 계산된다
    // (price=최저가, stockQuantity=합계) - 목록/카드 UI가 대표값 하나를 그대로 쓸 수 있도록.
    public record ProductResponse(
            Long id,
            CategoryResponse category,
            String name,
            String description,
            BigDecimal price,
            Integer stockQuantity,
            List<ProductImageResponse> images,
            List<OptionResponse> options,
            List<VariantResponse> variants,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) implements Serializable {
    }

    public record ProductSummaryResponse(
            Long id,
            Long categoryId,
            String name,
            BigDecimal price,
            Integer stockQuantity,
            String thumbnailUrl) {
    }

    public record ProductCreateRequest(
            @NotNull Long categoryId,
            @NotBlank String name,
            String description,
            @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal price,
            @NotNull @Min(0) Integer stockQuantity,
            List<String> imageUrls) {
    }

    public record ProductUpdateRequest(
            @NotNull Long categoryId,
            @NotBlank String name,
            String description,
            @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal price,
            @NotNull @Min(0) Integer stockQuantity,
            List<String> imageUrls) {
    }

    public record CategoryCreateRequest(@NotBlank String name, Long parentId) {
    }

    public record CreateOptionRequest(@NotBlank String name) {
    }

    public record CreateOptionValueRequest(@NotBlank String value) {
    }

    public record CreateVariantRequest(
            String sku,
            @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal price,
            @NotNull @Min(0) Integer stockQuantity,
            List<Long> optionValueIds) {
    }

    public record UpdateVariantRequest(
            String sku,
            @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal price,
            @NotNull @Min(0) Integer stockQuantity,
            boolean active) {
    }

    /**
     * 주문 생성 시 order.api가 가격을 확정하기 위해 조회하는 응답. productId/productName까지
     * 함께 돌려주는 이유는, 클라이언트가 보낸 productId-variantId 조합을 믿지 않고 variantId
     * 하나만으로 나머지를 전부 서버가 결정하기 위함이다(posselect #232).
     */
    public record VariantResolveResponse(
            Long variantId,
            Long productId,
            String productName,
            BigDecimal price,
            boolean active) {
    }
}
