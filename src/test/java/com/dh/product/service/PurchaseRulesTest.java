package com.dh.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dh.product.domain.ProductPolicy;
import com.dh.product.repository.ProductPolicyRepository;
import com.dh.product.repository.ProductRepository;

/** product.api#97 - 판매 기간(시작 포함·종료 미포함)과 1회 최대 구매 수량. */
@ExtendWith(MockitoExtension.class)
class PurchaseRulesTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 12, 0);

    @Mock
    private ProductPolicyRepository repository;
    @Mock
    private ProductRepository productRepository;

    private PurchaseRules rules() {
        return new PurchaseRules(repository, productRepository, Clock.fixed(NOW.atZone(KST).toInstant(), KST));
    }

    private static ProductPolicy policy(LocalDateTime start, LocalDateTime end, Integer max) {
        ProductPolicy p = new ProductPolicy(1L);
        p.setSaleStartAt(start);
        p.setSaleEndAt(end);
        p.setMaxPurchaseQuantity(max);
        return p;
    }

    @Test
    void noPolicyMeansNoLimit() {
        assertThat(rules().withinSalePeriod(null)).isTrue();
        assertThat(rules().maxPurchaseQuantity(null)).isNull();
    }

    @Test
    void salePeriodBoundaries() {
        assertThat(rules().withinSalePeriod(policy(NOW, null, null))).as("시작 시각 포함").isTrue();
        assertThat(rules().withinSalePeriod(policy(null, NOW, null))).as("종료 시각 미포함").isFalse();
        assertThat(rules().withinSalePeriod(policy(NOW.plusMinutes(1), null, null))).as("시작 전").isFalse();
        assertThat(rules().withinSalePeriod(policy(NOW.minusDays(1), NOW.plusDays(1), null))).isTrue();
    }

    @Test
    void checkCartRejectsOutOfPeriodAndOverMax() {
        given(repository.findById(1L)).willReturn(Optional.of(policy(null, NOW.minusDays(1), null)));
        assertThatThrownBy(() -> rules().checkCart(1L, 1)).isInstanceOf(PurchaseRuleViolationException.class)
                .hasMessage("purchase.outOfSalePeriod");

        given(repository.findById(2L)).willReturn(Optional.of(policy(null, null, 3)));
        rules().checkCart(2L, 3);
        assertThatThrownBy(() -> rules().checkCart(2L, 4)).isInstanceOf(PurchaseRuleViolationException.class)
                .hasMessage("purchase.maxQuantityExceeded")
                .satisfies(e -> assertThat(((PurchaseRuleViolationException) e).getMessageArgs()).containsExactly(3));
    }

    /** product.api#100 - 판매자가 정지·해지면 기간·수량과 무관하게 장바구니를 막는다. */
    @Test
    void checkCartRejectsSuspendedSeller() {
        given(productRepository.findIdsWithInactiveSeller(java.util.List.of(3L))).willReturn(java.util.List.of(3L));
        assertThatThrownBy(() -> rules().checkCart(3L, 1)).isInstanceOf(PurchaseRuleViolationException.class)
                .hasMessage("purchase.saleSuspended");
    }
}
