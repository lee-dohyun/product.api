package com.dh.product.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.dh.product.domain.ProductPolicy;
import com.dh.product.domain.ProductVariant;
import com.dh.product.repository.ProductPolicyRepository;
import com.dh.product.repository.ProductRepository;

/**
 * 판매 기간·1회 최대 구매 수량 판정의 단일 지점(product.api#97). 장바구니(CartService)와 주문 가격
 * 확정({@code /internal/variants/resolve}, {@code /internal/offers/resolve})이 같은 규칙을 쓴다 —
 * 오퍼 경로가 이 규칙을 안 타고 있던 것이 product.api#108 이었다. 값은 product_policies(#79) — 행이
 * 없거나 값이 null 이면 제한 없음.
 *
 * <p><b>목록 노출은 바꾸지 않는다</b>(2026-09-29 결정): 판매 기간 밖 상품도 목록·상세에는 보이고
 * 구매만 막는다. 기간 경계에서 목록·메인 캐시(5~10분 TTL)가 어긋나는 문제를 만들지 않기 위해서다.
 *
 * <p><b>판매자 정지·해지도 같은 방식이다</b>(product.api#100, 2026-09-30 결정): 판매자가 ACTIVE 가 아니면
 * 그 판매자 상품은 "판매 중단" — 목록·상세 노출은 그대로, 장바구니·주문만 막는다.
 */
@Component
public class PurchaseRules {

    private final ProductPolicyRepository policyRepository;
    private final ProductRepository productRepository;
    private final Clock clock;

    @Autowired
    public PurchaseRules(ProductPolicyRepository policyRepository, ProductRepository productRepository) {
        this(policyRepository, productRepository, Clock.systemDefaultZone());
    }

    PurchaseRules(ProductPolicyRepository policyRepository, ProductRepository productRepository, Clock clock) {
        this.policyRepository = policyRepository;
        this.productRepository = productRepository;
        this.clock = clock;
    }

    /** 판매자 사정(정지·해지)으로 판매 중단인 상품 id 들(product.api#100). */
    public Set<Long> saleSuspendedOf(Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(productRepository.findIdsWithInactiveSeller(productIds));
    }

    public boolean saleSuspended(Long productId) {
        return !saleSuspendedOf(List.of(productId)).isEmpty();
    }

    /** 상품 여러 개의 정책을 한 번에(주문 확정 경로의 N+1 방지). 정책 없는 상품은 맵에 없다. */
    public Map<Long, ProductPolicy> policiesOf(Collection<Long> productIds) {
        return policyRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(ProductPolicy::getProductId, Function.identity()));
    }

    /** 판매 기간 안인가. 정책이 없거나 기간이 비어 있으면 항상 true. 시작은 포함, 종료는 미포함. */
    public boolean withinSalePeriod(ProductPolicy policy) {
        if (policy == null) {
            return true;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        boolean started = policy.getSaleStartAt() == null || !now.isBefore(policy.getSaleStartAt());
        boolean notEnded = policy.getSaleEndAt() == null || now.isBefore(policy.getSaleEndAt());
        return started && notEnded;
    }

    public Integer maxPurchaseQuantity(ProductPolicy policy) {
        return policy == null ? null : policy.getMaxPurchaseQuantity();
    }

    /**
     * 주문 확정 경로들이 공유하는 구매 가능 판정(product.api#108).
     *
     * <p>{@code /internal/variants/resolve} 와 {@code /internal/offers/resolve} 가 <b>같은 식</b>을 써야 한다.
     * 식을 양쪽에 복사해 두면 한쪽만 고쳐지는 순간 차단이 조용히 샌다 — 실제로 오퍼 경로는 오퍼 상태만 보고
     * 있었고, order.api#14 가 주문 확정을 그 경로로 갈아타는 순간 숨김 상품(#74)·판매 기간 밖(#97)·판매자
     * 정지·해지(#100) 차단이 전부 풀리는 형태로 드러났다.
     *
     * <p>{@code saleSuspendedProductIds} 는 {@link #saleSuspendedOf} 로 <b>미리 묶어서</b> 넘긴다 —
     * 건당 조회하면 주문 항목 수만큼 쿼리가 나간다(product.api#72 와 같은 실수).
     */
    public boolean purchasable(ProductVariant variant, ProductPolicy policy, Set<Long> saleSuspendedProductIds) {
        return variant.isActive()
                && variant.getProduct().isPubliclyVisible()
                && withinSalePeriod(policy)
                && !saleSuspendedProductIds.contains(variant.getProduct().getId());
    }

    /**
     * 장바구니에 담을 수 있는가 — 판매 중단이 아니고, 판매 기간 안이고, 이 상품의 장바구니 합계 수량이 최대치 이하.
     * 합계는 같은 상품의 옵션(SKU)들을 모두 더한 값이다 — SKU 를 나눠 담아 한도를 넘는 것을 막는다.
     */
    public void checkCart(Long productId, int totalQuantityForProduct) {
        if (saleSuspended(productId)) {
            throw new PurchaseRuleViolationException("purchase.saleSuspended");
        }
        ProductPolicy policy = policyRepository.findById(productId).orElse(null);
        if (!withinSalePeriod(policy)) {
            throw new PurchaseRuleViolationException("purchase.outOfSalePeriod");
        }
        Integer max = maxPurchaseQuantity(policy);
        if (max != null && totalQuantityForProduct > max) {
            throw new PurchaseRuleViolationException("purchase.maxQuantityExceeded", max);
        }
    }
}
