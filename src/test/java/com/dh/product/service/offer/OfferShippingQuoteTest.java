package com.dh.product.service.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.dh.product.domain.Offer;
import com.dh.product.domain.OfferStatus;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductPolicy;
import com.dh.product.domain.ProductStatus;
import com.dh.product.domain.ProductVariant;
import com.dh.product.domain.Seller;
import com.dh.product.domain.ShippingFeeType;
import com.dh.product.dto.OfferDtos.ShippingQuoteItem;
import com.dh.product.repository.InventoryRepository;
import com.dh.product.repository.OfferRepository;
import com.dh.product.repository.ProductPolicyRepository;
import com.dh.product.repository.ProductRepository;
import com.dh.product.service.PurchaseRules;

/**
 * gateway#322 — order.api 가 받아 가는 배송비 견적. 금액 규칙 자체는 {@code ShippingFeeCalculatorTest} 가
 * 고정하고, 여기서는 "오퍼 가격 × 수량과 그 상품의 정책이 계산기에 제대로 들어가는가"만 본다.
 */
@ExtendWith(MockitoExtension.class)
class OfferShippingQuoteTest {

    private static final long PRODUCT_ID = 10L;
    private static final long OFFER_ID = 500L;

    @Mock
    private OfferRepository offerRepository;
    @Mock
    private ProductPolicyRepository productPolicyRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private InventoryRepository inventoryRepository;

    private OfferService offerService;
    private Offer offer;

    @BeforeEach
    void setUp() {
        offerService = new OfferService(offerRepository, new LowestPriceFeaturedOfferSelector(),
                new PurchaseRules(productPolicyRepository, productRepository), inventoryRepository);

        Seller seller = new Seller();
        ReflectionTestUtils.setField(seller, "id", 1L);
        Product product = new Product();
        product.setSeller(seller);
        product.setStatus(ProductStatus.LIVE);
        ReflectionTestUtils.setField(product, "id", PRODUCT_ID);
        // SKU 가격(99,000)과 오퍼 가격(20,000)을 다르게 둔다 — 견적은 주문이 청구하는 오퍼 가격을 써야 한다.
        ProductVariant variant = new ProductVariant(product, "sku", new BigDecimal("99000"));
        ReflectionTestUtils.setField(variant, "id", 100L);
        offer = new Offer(seller, variant, new BigDecimal("20000"), OfferStatus.ACTIVE);
        ReflectionTestUtils.setField(offer, "id", OFFER_ID);
    }

    @Test
    @DisplayName("오퍼 가격 × 수량이 기준액에 못 미치면 정책의 배송비를 돌려준다")
    void chargesWhenBelowThreshold() {
        given(offerRepository.findAllByIdWithVariantAndSeller(anyCollection())).willReturn(List.of(offer));
        given(productPolicyRepository.findAllById(anyCollection())).willReturn(List.of(conditional()));

        assertThat(offerService.quoteShipping(List.of(new ShippingQuoteItem(OFFER_ID, 2))).shippingFee())
                .isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("수량을 늘려 기준액을 채우면 무료다 — 같은 오퍼를 두 줄로 나눠 보내도 합산한다")
    void freeWhenQuantityReachesThreshold() {
        given(offerRepository.findAllByIdWithVariantAndSeller(anyCollection())).willReturn(List.of(offer));
        given(productPolicyRepository.findAllById(anyCollection())).willReturn(List.of(conditional()));

        assertThat(offerService.quoteShipping(List.of(
                new ShippingQuoteItem(OFFER_ID, 2), new ShippingQuoteItem(OFFER_ID, 1))).shippingFee())
                .isEqualByComparingTo("0");
        verify(productPolicyRepository, times(1)).findAllById(anyCollection());
    }

    @Test
    @DisplayName("정책이 없는 상품은 0원")
    void zeroWithoutPolicy() {
        given(offerRepository.findAllByIdWithVariantAndSeller(anyCollection())).willReturn(List.of(offer));
        given(productPolicyRepository.findAllById(anyCollection())).willReturn(List.of());

        assertThat(offerService.quoteShipping(List.of(new ShippingQuoteItem(OFFER_ID, 1))).shippingFee())
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("없는 오퍼가 섞이면 빼고 계산하지 않고 거부한다 — 적게 나온 배송비가 결제 금액이 되면 안 된다")
    void rejectsUnknownOffer() {
        given(offerRepository.findAllByIdWithVariantAndSeller(anyCollection())).willReturn(List.of(offer));

        assertThatThrownBy(() -> offerService.quoteShipping(List.of(
                new ShippingQuoteItem(OFFER_ID, 1), new ShippingQuoteItem(999L, 1))))
                .isInstanceOf(InvalidOfferResolveRequestException.class);
    }

    @Test
    @DisplayName("주문 당시 단가를 주면 그 단가로 기준액을 판정한다 — 부분 반품 뒤 남은 품목 재계산(gateway#320)")
    void usesGivenUnitPrice() {
        given(offerRepository.findAllByIdWithVariantAndSeller(anyCollection())).willReturn(List.of(offer));
        given(productPolicyRepository.findAllById(anyCollection())).willReturn(List.of(conditional()));

        // 지금 오퍼 가격(20,000)으로는 3개 = 60,000 무료지만, 주문 당시 단가 15,000 이면 45,000 이라 유료다.
        assertThat(offerService.quoteShipping(List.of(new ShippingQuoteItem(OFFER_ID, 3, new BigDecimal("15000"))))
                .shippingFee()).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("반품 배송비를 함께 돌려준다")
    void returnsReturnShippingFee() {
        ProductPolicy policy = conditional();
        policy.setReturnShippingFee(new BigDecimal("2500"));
        given(offerRepository.findAllByIdWithVariantAndSeller(anyCollection())).willReturn(List.of(offer));
        given(productPolicyRepository.findAllById(anyCollection())).willReturn(List.of(policy));

        assertThat(offerService.quoteShipping(List.of(new ShippingQuoteItem(OFFER_ID, 1))).returnShippingFee())
                .isEqualByComparingTo("2500");
    }

    private static ProductPolicy conditional() {
        ProductPolicy policy = new ProductPolicy(PRODUCT_ID);
        policy.setShippingFeeType(ShippingFeeType.CONDITIONAL);
        policy.setShippingFee(new BigDecimal("3000"));
        policy.setFreeShippingThreshold(new BigDecimal("50000"));
        return policy;
    }
}
