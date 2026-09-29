package com.dh.product.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.dh.product.domain.ProductPolicy;
import com.dh.product.repository.ProductPolicyRepository;

/**
 * 판매 기간·1회 최대 구매 수량 판정의 단일 지점(product.api#97). 장바구니(CartService)와 주문 가격
 * 확정(/internal/variants/resolve)이 같은 규칙을 쓴다. 값은 product_policies(#79) — 행이 없거나 값이
 * null 이면 제한 없음.
 *
 * <p><b>목록 노출은 바꾸지 않는다</b>(2026-09-29 결정): 판매 기간 밖 상품도 목록·상세에는 보이고
 * 구매만 막는다. 기간 경계에서 목록·메인 캐시(5~10분 TTL)가 어긋나는 문제를 만들지 않기 위해서다.
 */
@Component
public class PurchaseRules {

    private final ProductPolicyRepository policyRepository;
    private final Clock clock;

    @Autowired
    public PurchaseRules(ProductPolicyRepository policyRepository) {
        this(policyRepository, Clock.systemDefaultZone());
    }

    PurchaseRules(ProductPolicyRepository policyRepository, Clock clock) {
        this.policyRepository = policyRepository;
        this.clock = clock;
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
     * 장바구니에 담을 수 있는가 — 판매 기간 안이고, 이 상품의 장바구니 합계 수량이 최대치 이하.
     * 합계는 같은 상품의 옵션(SKU)들을 모두 더한 값이다 — SKU 를 나눠 담아 한도를 넘는 것을 막는다.
     */
    public void checkCart(Long productId, int totalQuantityForProduct) {
        ProductPolicy policy = policyRepository.findById(productId).orElse(null);
        if (!withinSalePeriod(policy)) {
            throw new PurchaseRuleViolationException("지금은 판매 기간이 아닙니다.");
        }
        Integer max = maxPurchaseQuantity(policy);
        if (max != null && totalQuantityForProduct > max) {
            throw new PurchaseRuleViolationException("이 상품은 1회 최대 " + max + "개까지 구매할 수 있습니다.");
        }
    }
}
