package com.dh.product.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

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

    /**
     * 배송비 견적 요청(gateway#322). order.api 가 {@code resolve} 로 확정한 오퍼와 수량을 그대로 보낸다 —
     * 가격·판매자·정책은 여기서 다시 읽으므로 호출자가 금액을 실어 보낼 수 없다.
     */
    public record ShippingQuoteRequest(@NotEmpty @Valid List<ShippingQuoteItem> items) {
    }

    /**
     * {@code unitPrice} 는 선택이다(gateway#320). 비우면 지금 오퍼 가격으로 계산하고(주문 생성), 채우면 그 단가로
     * 무료배송 기준액을 판정한다 — 부분 취소·반품 때 남은 품목의 배송비를 <b>주문 당시 가격</b>으로 다시 계산하려는
     * 것이다. 내부망 전용 경로라 이 값을 받아도 고객이 금액을 조작할 수 없다.
     */
    public record ShippingQuoteItem(@NotNull Long offerId, @NotNull @Min(1) Integer quantity,
            @DecimalMin("0") BigDecimal unitPrice) {

        public ShippingQuoteItem(Long offerId, Integer quantity) {
            this(offerId, quantity, null);
        }
    }

    /**
     * 배송비(판매자별 묶음 합계)와 반품 배송비(편도, 판매자별 최댓값 합계 — gateway#320). 계산 규칙은
     * {@code ShippingFeeCalculator}.
     */
    public record ShippingQuoteResponse(BigDecimal shippingFee, BigDecimal returnShippingFee) {
    }
}
