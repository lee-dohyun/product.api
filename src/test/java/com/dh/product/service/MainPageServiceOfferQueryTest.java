package com.dh.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import com.dh.product.domain.Category;
import com.dh.product.domain.Offer;
import com.dh.product.domain.OfferStatus;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductStatus;
import com.dh.product.domain.ProductVariant;
import com.dh.product.domain.Seller;
import com.dh.product.dto.ProductDtos.ProductSummaryResponse;
import com.dh.product.repository.BannerRepository;
import com.dh.product.repository.CategoryRepository;
import com.dh.product.repository.InventoryRepository;
import com.dh.product.repository.OfferRepository;
import com.dh.product.repository.ProductRepository;
import com.dh.product.repository.ProductVariantRepository;
import com.dh.product.service.offer.LowestPriceFeaturedOfferSelector;
import com.dh.product.service.offer.OfferService;

/**
 * product.api#72 회귀 방지 — 메인 페이지 목록(best/new/by-category)도 {@code ProductService} 목록과 같은
 * 요약 경로라, 상품마다 오퍼를 조회하면 N+1 이 된다. Redis 캐시가 있어 평소엔 덜 드러나지만 캐시가
 * 비는 순간(배포 직후, TTL 만료, 쓰기 후 evict)마다 그대로 나간다.
 *
 * <p>캐시는 이 테스트의 대상이 아니므로 스프링 컨텍스트 없이 서비스를 직접 만든다. {@code OfferService}
 * 는 목이 아니라 목 리포지토리 위의 실제 구현이다 — 쿼리 횟수는 리포지토리 호출로 센다.
 */
@ExtendWith(MockitoExtension.class)
class MainPageServiceOfferQueryTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private ProductVariantRepository productVariantRepository;
    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private BannerRepository bannerRepository;
    @Mock
    private OfferRepository offerRepository;
    @Mock
    private com.dh.product.repository.ProductPolicyRepository productPolicyRepository;

    private MainPageService mainPageService;

    @BeforeEach
    void setUp() {
        // PurchaseRules 는 목 리포지토리 위의 실제 구현이다 - 이 두 테스트가 검증하는 목록 경로는 구매
        // 가능 판정을 타지 않지만(가격·재고만 본다) 생성자가 요구하므로 동작하는 것을 넣어 준다(product.api#108).
        OfferService offerService = new OfferService(offerRepository, new LowestPriceFeaturedOfferSelector(),
                new PurchaseRules(productPolicyRepository, productRepository));
        mainPageService = new MainPageService(
                productRepository, categoryRepository, productVariantRepository,
                inventoryRepository, bannerRepository, offerService, new com.dh.product.config.SingleFlight());
    }

    @Test
    void 신상품_목록은_상품_수와_무관하게_오퍼를_한_번만_조회한다() {
        Category cat = new Category();
        org.springframework.test.util.ReflectionTestUtils.setField(cat, "id", 9101L);
        Seller firstParty = new Seller();
        org.springframework.test.util.ReflectionTestUtils.setField(firstParty, "id", 1L);

        List<Product> products = new ArrayList<>();
        List<ProductVariant> variants = new ArrayList<>();
        List<Offer> offers = new ArrayList<>();
        for (long i = 1; i <= 5; i++) {
            Product p = new Product();
            p.setName("상품" + i);
            p.setCategory(cat);
            p.setSeller(firstParty);
            org.springframework.test.util.ReflectionTestUtils.setField(p, "id", i);
            products.add(p);

            ProductVariant v = new ProductVariant(p, "v" + i, BigDecimal.valueOf(1000 * i));
            org.springframework.test.util.ReflectionTestUtils.setField(v, "id", 100 + i);
            variants.add(v);

            Offer o = new Offer(firstParty, v, BigDecimal.valueOf(700 * i), OfferStatus.ACTIVE);
            org.springframework.test.util.ReflectionTestUtils.setField(o, "id", 500 + i);
            offers.add(o);
        }

        given(productRepository.findByStatusOrderByCreatedAtDesc(eq(ProductStatus.LIVE), any(Pageable.class))).willReturn(products);
        given(productVariantRepository.findByProductIdIn(anyList())).willReturn(variants);
        given(inventoryRepository.findByVariantIdIn(anyList())).willReturn(List.of());
        given(offerRepository.findByVariantIdIn(anyCollection())).willReturn(offers);

        List<ProductSummaryResponse> result = mainPageService.getNewProducts(10);

        assertThat(result).hasSize(5);
        assertThat(result.get(0).price()).as("오퍼 가격이 실제로 반영된다(폴백이 아님)")
                .isEqualByComparingTo(BigDecimal.valueOf(700));
        verify(offerRepository, times(1)).findByVariantIdIn(anyCollection());
    }
}
