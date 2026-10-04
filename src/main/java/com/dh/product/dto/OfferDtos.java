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
            Integer maxPurchaseQuantity,
            /**
             * 이 variant 의 현재 재고(order.api#47). 주문 생성 때 "지금 살 수 있는 수량인가"를 미리 거르는
             * <b>조회용</b> 값이다 — 캐시를 거치지 않고 읽지만 예약이 아니므로, 실제 판정은 결제 때의
             * 차감({@code /internal/inventory/deduct})이 한다. 재고 행이 없으면 0.
             */
            int stockQuantity) {
    }
}
