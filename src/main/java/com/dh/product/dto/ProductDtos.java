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
    //
    // listPrice 이하 6개는 product.api#28(노출 속성) 추가분. 할인율은 저장하지 않고
    // listPrice와 price(판매가)로 매번 파생한다 - 둘을 따로 저장하면 갈라질 수 있다.
    // ratingAvg/reviewCount는 리뷰 기능이 없는 동안의 비정규화 컬럼이다(관리자가 직접 입력) -
    // 리뷰 기능이 생기면 실제 집계값으로 교체될 자리다.
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
            LocalDateTime updatedAt,
            BigDecimal listPrice,
            BigDecimal ratingAvg,
            Integer reviewCount,
            String shippingBadge,
            boolean freeShipping,
            String brand) implements Serializable {
    }

    public record ProductSummaryResponse(
            Long id,
            Long categoryId,
            String name,
            BigDecimal price,
            Integer stockQuantity,
            String thumbnailUrl,
            BigDecimal listPrice,
            BigDecimal ratingAvg,
            Integer reviewCount,
            String shippingBadge,
            boolean freeShipping,
            String brand) {
    }

    public record ProductCreateRequest(
            @NotNull Long categoryId,
            @NotBlank String name,
            String description,
            @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal price,
            @NotNull @Min(0) Integer stockQuantity,
            List<String> imageUrls,
            @DecimalMin(value = "0", inclusive = true) BigDecimal listPrice,
            @DecimalMin(value = "0", inclusive = true) BigDecimal ratingAvg,
            @Min(0) Integer reviewCount,
            String shippingBadge,
            boolean freeShipping,
            String brand) {
    }

    public record ProductUpdateRequest(
            @NotNull Long categoryId,
            @NotBlank String name,
            String description,
            @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal price,
            @NotNull @Min(0) Integer stockQuantity,
            List<String> imageUrls,
            @DecimalMin(value = "0", inclusive = true) BigDecimal listPrice,
            @DecimalMin(value = "0", inclusive = true) BigDecimal ratingAvg,
            @Min(0) Integer reviewCount,
            String shippingBadge,
            boolean freeShipping,
            String brand) {
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
