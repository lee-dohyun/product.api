package com.dh.product.service.shipping;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.dh.product.domain.ProductPolicy;
import com.dh.product.domain.ShippingFeeType;
import com.dh.product.service.shipping.ShippingFeeCalculator.Line;

/**
 * 배송비 계산 규칙(gateway#322, 2026-10-14 결정): 판매자별로 묶어 1회, 무료배송 기준은 할인 전
 * 상품 금액, 정책이 없는 상품은 0원.
 */
class ShippingFeeCalculatorTest {

    private static final long SELLER_A = 1L;
    private static final long SELLER_B = 2L;

    @Test
    @DisplayName("품목이 없으면 0원")
    void emptyIsZero() {
        assertThat(ShippingFeeCalculator.total(List.of())).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("정책이 없거나 배송비 유형이 비어 있으면 0원 — 금액을 지어내지 않는다")
    void noPolicyIsZero() {
        assertThat(ShippingFeeCalculator.total(List.of(
                line(SELLER_A, "10000", null),
                line(SELLER_A, "10000", new ProductPolicy(7L))))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("무료배송 상품은 0원")
    void freeIsZero() {
        assertThat(ShippingFeeCalculator.total(List.of(line(SELLER_A, "10000", policy(ShippingFeeType.FREE, null, null)))))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("유료배송 상품은 금액과 무관하게 배송비를 받는다")
    void paidAlwaysCharges() {
        assertThat(ShippingFeeCalculator.total(List.of(line(SELLER_A, "900000", policy(ShippingFeeType.PAID, "3000", null)))))
                .isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("조건부 무료: 기준액 미만이면 받고, 기준액과 같거나 넘으면 무료")
    void conditionalThreshold() {
        ProductPolicy conditional = policy(ShippingFeeType.CONDITIONAL, "3000", "50000");

        assertThat(ShippingFeeCalculator.total(List.of(line(SELLER_A, "49999", conditional)))).isEqualByComparingTo("3000");
        assertThat(ShippingFeeCalculator.total(List.of(line(SELLER_A, "50000", conditional)))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("같은 판매자 상품은 합계로 기준액을 판정한다 — 다른 상품 금액도 합산된다")
    void thresholdUsesSellerSubtotal() {
        ProductPolicy conditional = policy(ShippingFeeType.CONDITIONAL, "3000", "50000");

        assertThat(ShippingFeeCalculator.total(List.of(
                line(SELLER_A, "30000", conditional),
                line(SELLER_A, "20000", null)))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("같은 판매자 안에서는 1회만, 가장 높은 배송비를 받는다")
    void oneChargePerSellerUsingHighestFee() {
        assertThat(ShippingFeeCalculator.total(List.of(
                line(SELLER_A, "10000", policy(ShippingFeeType.PAID, "2500", null)),
                line(SELLER_A, "10000", policy(ShippingFeeType.PAID, "4000", null)),
                line(SELLER_A, "10000", policy(ShippingFeeType.FREE, null, null))))).isEqualByComparingTo("4000");
    }

    @Test
    @DisplayName("판매자가 다르면 각각 받고, 기준액도 판매자별로 따로 본다")
    void sellersAreChargedSeparately() {
        ProductPolicy conditional = policy(ShippingFeeType.CONDITIONAL, "3000", "50000");

        // A 는 30,000 + B 는 30,000 — 주문 합계는 60,000 이지만 판매자별로는 둘 다 기준 미달이다.
        assertThat(ShippingFeeCalculator.total(List.of(
                line(SELLER_A, "30000", conditional),
                line(SELLER_B, "30000", conditional)))).isEqualByComparingTo("6000");
    }

    @Test
    @DisplayName("배송비 금액이 비어 있는 정책은 0원으로 본다")
    void missingFeeAmountIsZero() {
        assertThat(ShippingFeeCalculator.total(List.of(line(SELLER_A, "10000", policy(ShippingFeeType.PAID, null, null)))))
                .isEqualByComparingTo("0");
    }

    private static Line line(long sellerId, String amount, ProductPolicy policy) {
        return new Line(sellerId, new BigDecimal(amount), policy);
    }

    private static ProductPolicy policy(ShippingFeeType type, String fee, String threshold) {
        ProductPolicy p = new ProductPolicy(7L);
        p.setShippingFeeType(type);
        p.setShippingFee(fee == null ? null : new BigDecimal(fee));
        p.setFreeShippingThreshold(threshold == null ? null : new BigDecimal(threshold));
        return p;
    }
}
