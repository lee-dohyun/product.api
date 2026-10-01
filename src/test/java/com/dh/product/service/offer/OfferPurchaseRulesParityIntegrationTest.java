package com.dh.product.service.offer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.dh.product.config.CacheNames;
import com.dh.product.domain.OfferStatus;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductPolicy;
import com.dh.product.domain.ProductStatus;
import com.dh.product.domain.Seller;
import com.dh.product.domain.SellerStatus;
import com.dh.product.domain.SellerType;
import com.dh.product.dto.OfferDtos.OfferResolveResponse;
import com.dh.product.dto.ProductDtos.ProductCreateRequest;
import com.dh.product.dto.ProductDtos.VariantResolveResponse;
import com.dh.product.repository.OfferRepository;
import com.dh.product.repository.ProductPolicyRepository;
import com.dh.product.repository.ProductRepository;
import com.dh.product.repository.SellerRepository;
import com.dh.product.service.ProductService;

/**
 * product.api#108 — {@code /internal/offers/resolve} 와 {@code /internal/variants/resolve} 의
 * <b>구매 가능 판정이 같은지</b>를 실제 DB 로 대조한다.
 *
 * <p>왜 대조 형태로 쓰는가: 오퍼 경로는 오퍼 상태만 보고 있어서, order.api#14 가 주문 확정을 그 경로로
 * 갈아타는 순간 숨김 상품(#74)·판매 기간 밖(#97)·판매자 정지(#100) 차단과 1회 최대 구매 수량(#97)이
 * 한꺼번에 사라진다. 두 응답을 나란히 비교하지 않으면 "오퍼 쪽만 true" 를 놓친다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class OfferPurchaseRulesParityIntegrationTest {

    /** V15 가 CREATE EXTENSION vector 를 하므로 순정 postgres 이미지로는 부팅이 실패한다. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withStartupTimeout(Duration.ofMinutes(3));

    @TestConfiguration
    static class LocalCacheConfig {
        @Bean
        @Primary
        CacheManager testCacheManager() {
            return new ConcurrentMapCacheManager(
                    CacheNames.PRODUCT,
                    CacheNames.MAIN_BEST,
                    CacheNames.MAIN_NEW,
                    CacheNames.MAIN_BY_CATEGORY);
        }
    }

    private static final Long DEMO_CATEGORY_ID = 9101L;

    @Autowired
    private OfferService offerService;
    @Autowired
    private ProductService productService;
    @Autowired
    private OfferRepository offerRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private ProductPolicyRepository policyRepository;
    @Autowired
    private SellerRepository sellerRepository;

    private record Fixture(Long productId, Long variantId, Long offerId) {
    }

    private Fixture create(String name, Long sellerId) {
        Long productId = productService.createProduct(new ProductCreateRequest(
                DEMO_CATEGORY_ID, name, "설명", new BigDecimal("10000"), 5,
                List.of("https://image.posselect.com/cdn/products/x.png"),
                null, null, null, null, false, null, sellerId, ProductStatus.LIVE.name())).id();
        Long variantId = productService.listVariants(productId).get(0).id();
        Long offerId = offerRepository.findByVariantIdAndStatus(variantId, OfferStatus.ACTIVE).get(0).getId();
        return new Fixture(productId, variantId, offerId);
    }

    /** 세 경로(variants/resolve, offers/resolve?ids, offers/resolve?variantIds)의 판정을 한 번에 본다. */
    private void assertParity(Fixture f, boolean expectedActive, Integer expectedMaxQuantity) {
        VariantResolveResponse byVariant = productService.resolveVariants(List.of(f.variantId())).get(0);
        OfferResolveResponse byOfferId = offerService.resolveOffers(List.of(f.offerId())).get(0);
        List<OfferResolveResponse> byVariantIds =
                offerService.resolveFeaturedOffersByVariant(List.of(f.variantId()));

        assertThat(byVariant.active()).as("variants/resolve active").isEqualTo(expectedActive);
        assertThat(byVariant.maxPurchaseQuantity()).as("variants/resolve maxPurchaseQuantity")
                .isEqualTo(expectedMaxQuantity);

        assertThat(byOfferId.active()).as("offers/resolve?ids active — variants/resolve 와 같아야 한다")
                .isEqualTo(expectedActive);
        assertThat(byOfferId.maxPurchaseQuantity()).as("offers/resolve?ids maxPurchaseQuantity")
                .isEqualTo(expectedMaxQuantity);

        if (expectedActive) {
            // ?variantIds= 는 ACTIVE 오퍼만 후보로 모으므로, 오퍼 자체가 ACTIVE 인 이 픽스처들에서는 항상 1건이다.
            assertThat(byVariantIds).singleElement().satisfies(r -> {
                assertThat(r.active()).as("offers/resolve?variantIds active").isTrue();
                assertThat(r.maxPurchaseQuantity()).isEqualTo(expectedMaxQuantity);
            });
        } else {
            assertThat(byVariantIds).singleElement().satisfies(r -> assertThat(r.active())
                    .as("offers/resolve?variantIds — 오퍼는 ACTIVE 지만 상품/판매자 사정으로 주문 불가여야 한다")
                    .isFalse());
        }
    }

    @Test
    @DisplayName("정상 LIVE 상품은 양쪽 모두 주문 가능")
    void liveProductIsPurchasableOnBothPaths() {
        assertParity(create("정상 상품", null), true, null);
    }

    @Test
    @DisplayName("숨김(LIVE 아님) 상품은 오퍼 경로에서도 주문 불가 — product.api#74")
    void hiddenProductIsBlockedOnOfferPath() {
        Fixture f = create("숨김 상품", null);
        Product product = productRepository.findById(f.productId()).orElseThrow();
        product.setStatus(ProductStatus.PAUSED);
        productRepository.save(product);

        assertParity(f, false, null);
    }

    @Test
    @DisplayName("판매 기간이 지난 상품은 오퍼 경로에서도 주문 불가 — product.api#97")
    void outOfSalePeriodIsBlockedOnOfferPath() {
        Fixture f = create("기간 지난 상품", null);
        ProductPolicy policy = new ProductPolicy(f.productId());
        policy.setSaleEndAt(LocalDateTime.now().minusDays(1));
        policyRepository.save(policy);

        assertParity(f, false, null);
    }

    @Test
    @DisplayName("판매자가 정지되면 그 상품은 오퍼 경로에서도 주문 불가 — product.api#100")
    void suspendedSellerIsBlockedOnOfferPath() {
        Seller supplier = new Seller();
        supplier.setName("정지될 공급사");
        supplier.setBusinessRegistrationNo("333-33-33333");
        supplier.setRepresentativeName("박공급");
        supplier.setAddress("서울시");
        supplier.setPhone("010-3333-4444");
        supplier.setEmail("supplier3@example.com");
        supplier.setStatus(SellerStatus.ACTIVE);
        supplier.setType(SellerType.SUPPLIER);
        sellerRepository.save(supplier);

        Fixture f = create("정지 판매자 상품", supplier.getId());
        supplier.setStatus(SellerStatus.SUSPENDED);
        sellerRepository.save(supplier);

        assertParity(f, false, null);
    }

    @Test
    @DisplayName("1회 최대 구매 수량이 오퍼 응답에도 실린다 — 없으면 order.api 가 제한을 못 걸어 무력화된다")
    void maxPurchaseQuantityIsCarriedOnOfferPath() {
        Fixture f = create("수량 제한 상품", null);
        ProductPolicy policy = new ProductPolicy(f.productId());
        policy.setMaxPurchaseQuantity(2);
        policyRepository.save(policy);

        assertParity(f, true, 2);
    }
}
