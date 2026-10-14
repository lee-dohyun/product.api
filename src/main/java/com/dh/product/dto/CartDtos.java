package com.dh.product.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class CartDtos {

    public record CartItemAddRequest(@NotNull Long variantId, @NotNull @Min(1) Integer quantity) {
    }

    public record CartItemUpdateRequest(@NotNull @Min(0) Integer quantity) {
    }

    public record CartItemResponse(
            Long variantId,
            Long productId,
            String name,
            BigDecimal price,
            Integer quantity,
            String thumbnailUrl) {
    }

    /**
     * {@code totalPrice} 는 상품 금액 합계이고 배송비를 포함하지 않는다. {@code shippingFee} 는 주문할 때
     * 청구될 배송비다(gateway#322) - 주문과 같은 {@code ShippingFeeCalculator} 로 계산한다.
     */
    public record CartResponse(List<CartItemResponse> items, BigDecimal totalPrice, BigDecimal shippingFee) {
    }
}
