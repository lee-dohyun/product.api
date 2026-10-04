package com.dh.product.service.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.dh.product.domain.Inventory;
import com.dh.product.domain.Offer;
import com.dh.product.domain.OfferStatus;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductStatus;
import com.dh.product.domain.ProductVariant;
import com.dh.product.domain.Seller;
import com.dh.product.dto.OfferDtos.OfferResolveResponse;
import com.dh.product.repository.InventoryRepository;
import com.dh.product.repository.OfferRepository;
import com.dh.product.repository.ProductPolicyRepository;
import com.dh.product.repository.ProductRepository;
import com.dh.product.service.PurchaseRules;

/**
 * product.api#108 — 오퍼 확정에 구매 가능 판정을 붙이면서 상품마다 정책·판매자 정지를 조회하면
 * 주문 항목 수만큼 쿼리가 나간다(product.api#72 에서 실제로 겪은 실수). 항목이 몇 개든 각각 1회임을 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class OfferResolveQueryCountTest {

    @Mock
    private OfferRepository offerRepository;
    @Mock
    private ProductPolicyRepository productPolicyRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private InventoryRepository inventoryRepository;

    private OfferService offerService;
    private List<Offer> offers;

    @BeforeEach
    void setUp() {
        offerService = new OfferService(offerRepository, new LowestPriceFeaturedOfferSelector(),
                new PurchaseRules(productPolicyRepository, productRepository), inventoryRepository);

        Seller seller = new Seller();
        ReflectionTestUtils.setField(seller, "id", 1L);
        seller.setName("포스셀렉트");

        offers = new ArrayList<>();
        for (long i = 1; i <= 4; i++) {
            Product p = new Product();
            p.setName("상품" + i);
            p.setSeller(seller);
            p.setStatus(ProductStatus.LIVE);
            ReflectionTestUtils.setField(p, "id", i);

            ProductVariant v = new ProductVariant(p, "sku" + i, BigDecimal.valueOf(1000 * i));
            ReflectionTestUtils.setField(v, "id", 100 + i);

            Offer o = new Offer(seller, v, BigDecimal.valueOf(900 * i), OfferStatus.ACTIVE);
            ReflectionTestUtils.setField(o, "id", 500 + i);
            offers.add(o);
        }
    }

    @Test
    @DisplayName("offerId 4건을 확정해도 정책·판매자 정지 조회는 각각 1회다")
    void resolveOffersQueriesPolicyAndSuspensionOnce() {
        given(offerRepository.findAllByIdWithVariantAndSeller(anyCollection())).willReturn(offers);
        given(productPolicyRepository.findAllById(anyCollection())).willReturn(List.of());
        given(productRepository.findIdsWithInactiveSeller(anyCollection())).willReturn(List.of());

        List<OfferResolveResponse> resolved = offerService.resolveOffers(List.of(501L, 502L, 503L, 504L));

        assertThat(resolved).hasSize(4);
        assertThat(resolved).allSatisfy(r -> assertThat(r.active())
                .as("LIVE 상품·활성 variant·정책 없음이면 주문 가능").isTrue());
        verify(productPolicyRepository, times(1)).findAllById(anyCollection());
        verify(productRepository, times(1)).findIdsWithInactiveSeller(anyCollection());
    }

    @Test
    @DisplayName("variantIds 경로도 조회가 각각 1회다")
    void resolveByVariantQueriesPolicyAndSuspensionOnce() {
        given(offerRepository.findAllByVariantIdInWithVariantAndSeller(anyCollection(), any(OfferStatus.class)))
                .willReturn(offers);
        given(productPolicyRepository.findAllById(anyCollection())).willReturn(List.of());
        given(productRepository.findIdsWithInactiveSeller(anyCollection())).willReturn(List.of());

        List<OfferResolveResponse> resolved = offerService.resolveFeaturedOffersByVariant(
                List.of(101L, 102L, 103L, 104L));

        assertThat(resolved).hasSize(4);
        verify(productPolicyRepository, times(1)).findAllById(anyCollection());
        verify(productRepository, times(1)).findIdsWithInactiveSeller(anyCollection());
    }

    @Test
    @DisplayName("재고는 항목 수와 무관하게 조회 1회로 싣고, 재고 행이 없는 variant 는 0 이다 (order.api#47)")
    void resolveCarriesStockWithSingleQuery() {
        given(offerRepository.findAllByIdWithVariantAndSeller(anyCollection())).willReturn(offers);
        given(productPolicyRepository.findAllById(anyCollection())).willReturn(List.of());
        given(productRepository.findIdsWithInactiveSeller(anyCollection())).willReturn(List.of());
        // variant 101·102 만 재고 행이 있다.
        given(inventoryRepository.findByVariantIdIn(anyCollection())).willReturn(List.of(
                new Inventory(offers.get(0).getVariant(), 7),
                new Inventory(offers.get(1).getVariant(), 0)));

        List<OfferResolveResponse> resolved = offerService.resolveOffers(List.of(501L, 502L, 503L, 504L));

        assertThat(resolved).extracting(OfferResolveResponse::variantId, OfferResolveResponse::stockQuantity)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(101L, 7),
                        org.assertj.core.groups.Tuple.tuple(102L, 0),
                        org.assertj.core.groups.Tuple.tuple(103L, 0),
                        org.assertj.core.groups.Tuple.tuple(104L, 0));
        verify(inventoryRepository, times(1)).findByVariantIdIn(anyCollection());
    }
}
