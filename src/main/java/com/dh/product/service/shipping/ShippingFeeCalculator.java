package com.dh.product.service.shipping;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dh.product.domain.ProductPolicy;
import com.dh.product.domain.ShippingFeeType;

/**
 * 배송비 계산 규칙의 유일한 구현(gateway#322). 장바구니 표시와 주문 청구가 <b>같은 식</b>을 써야
 * 화면에 보인 금액과 결제 금액이 갈라지지 않는다 — order.api 는 이 규칙을 복제하지 않고
 * {@code /internal/offers/shipping-quote} 로 받아 간다.
 *
 * <p>규칙(2026-10-14 결정):
 * <ul>
 *   <li>출처는 상품 판매 정책({@link ProductPolicy})이다 — 상품 상세의 "배송비" 줄과 같은 값.
 *       정책이 없거나 배송비 유형이 비어 있으면 0원이다. 금액을 추정해 채우지 않는다.</li>
 *   <li>판매자별로 묶어 1회만 받는다. 묶음 안에서는 품목별 배송비 중 가장 높은 금액.</li>
 *   <li>조건부 무료의 기준액은 그 판매자 상품의 <b>할인 전</b> 합계로 판정한다(등급 할인은 order.api 가
 *       이 계산 뒤에 따로 뺀다).</li>
 * </ul>
 *
 * <p>미구현: 제주·도서산간 추가 배송비({@code jejuExtraFee}, {@code islandExtraFee}). 배송지가 그
 * 권역인지 판정할 데이터(우편번호 권역표)가 없어 청구하지 않는다.
 */
public final class ShippingFeeCalculator {

    private ShippingFeeCalculator() {
    }

    /** 품목 한 줄. {@code amount} 는 단가 × 수량(할인 전), {@code policy} 는 없으면 null. */
    public record Line(Long sellerId, BigDecimal amount, ProductPolicy policy) {
    }

    public static BigDecimal total(List<Line> lines) {
        Map<Long, List<Line>> bySeller = new LinkedHashMap<>();
        for (Line line : lines) {
            bySeller.computeIfAbsent(line.sellerId(), k -> new ArrayList<>()).add(line);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (List<Line> group : bySeller.values()) {
            BigDecimal subtotal = group.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            total = total.add(group.stream()
                    .map(line -> feeOf(line.policy(), subtotal))
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO));
        }
        return total;
    }

    private static BigDecimal feeOf(ProductPolicy policy, BigDecimal sellerSubtotal) {
        if (policy == null || policy.getShippingFeeType() == null || policy.getShippingFee() == null) {
            return BigDecimal.ZERO;
        }
        ShippingFeeType type = policy.getShippingFeeType();
        if (type == ShippingFeeType.FREE) {
            return BigDecimal.ZERO;
        }
        if (type == ShippingFeeType.CONDITIONAL && policy.getFreeShippingThreshold() != null
                && sellerSubtotal.compareTo(policy.getFreeShippingThreshold()) >= 0) {
            return BigDecimal.ZERO;
        }
        return policy.getShippingFee();
    }
}
