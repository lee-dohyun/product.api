package com.dh.product.service.offer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.dh.product.config.CacheNames;
import com.dh.product.domain.OfferStatus;
import com.dh.product.dto.OfferDtos.OfferResolveResponse;
import com.dh.product.dto.ProductDtos.CreateVariantRequest;
import com.dh.product.dto.ProductDtos.ProductCreateRequest;
import com.dh.product.dto.ProductDtos.ProductResponse;
import com.dh.product.repository.OfferRepository;
import com.dh.product.service.ProductService;

/**
 * product.api#31 회귀 테스트.
 *
 * <p>가장 중요한 성질은 <b>가격 전환의 무해함</b>이다 - 대표가의 출처를 variant.price 에서
 * 대표 오퍼로 바꿨는데, V17 백필과 {@code createFirstPartyOffer} 덕분에 값이 전환 전과
 * 같아야 한다. 오퍼가 하나라도 빠지면 그 상품 가격이 0원으로 보이므로 여기서 잡는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class OfferIntegrationTest {

    /** V15 가 CREATE EXTENSION vector 를 하므로 순정 postgres 이미지로는 부팅이 실패한다. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

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
    private JdbcTemplate jdbcTemplate;

    private Long createProduct(String name, String price) {
        return productService.createProduct(new ProductCreateRequest(
                DEMO_CATEGORY_ID, name, "설명", new BigDecimal(price), 10,
                List.of("https://image.posselect.com/cdn/products/x.png"),
                null, null, null, null, false, null, null, null)).id();
    }

    @Test
    @DisplayName("V17 이 기존 variant 전부에 자사 오퍼를 백필한다 — 오퍼 없는 SKU 가 남으면 그 상품 가격이 0원이 된다")
    void everyVariantHasAnOffer() {
        Integer orphanVariants = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM product_variants v "
                        + "WHERE NOT EXISTS (SELECT 1 FROM offers o WHERE o.product_variant_id = v.id)",
                Integer.class);

        assertThat(orphanVariants).isZero();
    }

    @Test
    @DisplayName("백필된 오퍼 가격이 variant 가격과 일치한다 — 가격 출처 전환이 값을 바꾸지 않아야 한다")
    void backfilledOfferPriceMatchesVariantPrice() {
        Integer mismatched = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM offers o JOIN product_variants v ON v.id = o.product_variant_id "
                        + "WHERE o.price <> v.price",
                Integer.class);

        assertThat(mismatched).isZero();
    }

    @Test
    @DisplayName("백필된 오퍼는 자사(seller_id=1) 명의이고 variant 활성 여부를 상태로 옮긴다")
    void backfillMirrorsSellerAndActiveFlag() {
        Integer wrongStatus = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM offers o JOIN product_variants v ON v.id = o.product_variant_id "
                        + "WHERE (v.active AND o.status <> 'ACTIVE') OR (NOT v.active AND o.status = 'ACTIVE')",
                Integer.class);
        Integer nonFirstParty = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM offers WHERE seller_id <> 1", Integer.class);

        assertThat(wrongStatus).isZero();
        assertThat(nonFirstParty).isZero();
    }

    @Test
    @DisplayName("새로 만든 상품/SKU 에도 오퍼가 함께 생긴다 — 백필만으로는 신규 SKU 가 비어 버린다")
    void newVariantsGetAnOffer() {
        Long productId = createProduct("오퍼 테스트 상품", "12000");

        var variants = productService.listVariants(productId);
        assertThat(variants).hasSize(1);
        assertThat(offerRepository.findByVariantIdAndStatus(variants.get(0).id(), OfferStatus.ACTIVE))
                .hasSize(1);

        productService.createVariant(productId, new CreateVariantRequest(
                "SKU-OFFER-1", new BigDecimal("15000"), 5, List.of()));

        var allVariants = productService.listVariants(productId);
        assertThat(allVariants).hasSize(2);
        allVariants.forEach(v -> assertThat(offerRepository.findByVariantIdIn(List.of(v.id())))
                .as("variant %s 에 오퍼가 없다", v.id())
                .isNotEmpty());
    }

    @Test
    @DisplayName("대표가는 대표 오퍼(최저 ACTIVE)의 가격이다")
    void representativePriceComesFromFeaturedOffer() {
        Long productId = createProduct("대표가 테스트", "20000");
        productService.createVariant(productId, new CreateVariantRequest(
                "SKU-CHEAP", new BigDecimal("9000"), 5, List.of()));

        ProductResponse response = productService.getProduct(productId);

        assertThat(response.price()).isEqualByComparingTo(new BigDecimal("9000"));
    }

    @Test
    @DisplayName("offers/resolve 는 offerId 만으로 가격·상품·판매자를 서버가 확정해 돌려준다")
    void resolveReturnsServerDeterminedPriceAndSeller() {
        Long productId = createProduct("리졸브 테스트", "31000");
        Long variantId = productService.listVariants(productId).get(0).id();
        Long offerId = offerRepository.findByVariantIdAndStatus(variantId, OfferStatus.ACTIVE).get(0).getId();

        List<OfferResolveResponse> resolved = offerService.resolveOffers(List.of(offerId));

        assertThat(resolved).singleElement().satisfies(r -> {
            assertThat(r.offerId()).isEqualTo(offerId);
            assertThat(r.variantId()).isEqualTo(variantId);
            assertThat(r.productId()).isEqualTo(productId);
            assertThat(r.productName()).isEqualTo("리졸브 테스트");
            assertThat(r.sellerId()).isEqualTo(1L);
            assertThat(r.sellerName()).isEqualTo("포스셀렉트");
            assertThat(r.price()).isEqualByComparingTo(new BigDecimal("31000"));
            assertThat(r.active()).isTrue();
        });
    }

    @Test
    @DisplayName("존재하지 않는 offerId 는 예외가 아니라 결과 누락으로 돌아온다 — 호출자가 개수로 판정한다")
    void resolveSkipsUnknownIdsInsteadOfThrowing() {
        Long productId = createProduct("누락 판정 테스트", "5000");
        Long variantId = productService.listVariants(productId).get(0).id();
        Long offerId = offerRepository.findByVariantIdAndStatus(variantId, OfferStatus.ACTIVE).get(0).getId();

        List<OfferResolveResponse> resolved = offerService.resolveOffers(List.of(offerId, 99999999L));

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).offerId()).isEqualTo(offerId);
    }
}
