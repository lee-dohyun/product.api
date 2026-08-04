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

    public record CategoryResponse(Long id, String name) {
    }

    public record ProductImageResponse(Long id, String imageUrl, Short sortOrder) {
    }

    // 단일 상품 조회 응답 - Redis에 JSON으로 캐싱되므로 Serializable
    public record ProductResponse(
            Long id,
            CategoryResponse category,
            String name,
            String description,
            BigDecimal price,
            Integer stockQuantity,
            List<ProductImageResponse> images,
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

    public record CategoryCreateRequest(@NotBlank String name) {
    }
}
