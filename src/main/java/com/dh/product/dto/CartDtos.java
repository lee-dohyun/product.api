package com.dh.product.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class CartDtos {

    public record CartItemAddRequest(@NotNull Long productId, @NotNull @Min(1) Integer quantity) {
    }

    public record CartItemUpdateRequest(@NotNull @Min(0) Integer quantity) {
    }

    public record CartItemResponse(
            Long productId,
            String name,
            BigDecimal price,
            Integer quantity,
            String thumbnailUrl) {
    }

    public record CartResponse(List<CartItemResponse> items, BigDecimal totalPrice) {
    }
}
