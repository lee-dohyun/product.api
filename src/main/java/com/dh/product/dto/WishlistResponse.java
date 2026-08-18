package com.dh.product.dto;

import com.dh.product.domain.WishlistItem;

public record WishlistResponse(
    Long id,
    Long productId,
    String productName
) {
    public static WishlistResponse from(WishlistItem item) {
        return new WishlistResponse(
            item.getId(),
            item.getProduct().getId(),
            item.getProduct().getName()
        );
    }
}
