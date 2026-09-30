package com.dh.product.dto;

import java.math.BigDecimal;

public class OfferDtos {

    /**
     * order.api 가 주문 금액을 확정하기 위해 조회하는 응답.
     * {@code /internal/variants/resolve} 의 후속이며 판매자까지 함께 확정한다.
     *
     * <p>요청은 {@code offerId} 만 받는다 - 클라이언트가 보낸 가격·판매자·상품 조합을
     * 하나도 믿지 않고 서버가 전부 결정하기 위함이다(product.api#5 취약점의 재발 방지선).
     */
    public record OfferResolveResponse(
            Long offerId,
            Long variantId,
            Long productId,
            String productName,
            Long sellerId,
            String sellerName,
            BigDecimal price,
            BigDecimal shippingFee,
            boolean freeShipping,
            Short leadTimeDays,
            /**
             * 주문 가능 여부. 오퍼 상태만이 아니라 숨김 상품·판매 기간·판매자 정지까지 합친 값이다 —
             * {@code /internal/variants/resolve} 의 {@code active} 와 같은 의미여야 한다(product.api#108).
             */
            boolean active,
            /** 상품별 1회 최대 구매 수량. 제한 없으면 null (product.api#97). */
            Integer maxPurchaseQuantity) {
    }
}
