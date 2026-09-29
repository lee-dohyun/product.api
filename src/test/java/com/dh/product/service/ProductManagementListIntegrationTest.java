package com.dh.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

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
import com.dh.product.domain.ProductStatus;
import com.dh.product.dto.ProductDtos.ProductCreateRequest;

/**
 * admin.front#50 - 관리자 상품 목록은 상태·판매자까지 준다. 판매자(LAZY)를 읽으므로 실제 DB 로 확인한다
 * (open-in-view 가 꺼져 있어 트랜잭션 밖에서 읽으면 LazyInitializationException - product.api#88 과 같은 유형).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ProductManagementListIntegrationTest {

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
                    CacheNames.PRODUCT, CacheNames.MAIN_BEST, CacheNames.MAIN_NEW, CacheNames.MAIN_BY_CATEGORY);
        }
    }

    @Autowired
    private ProductService productService;

    private Long create(String name, String status) {
        return productService.createProduct(new ProductCreateRequest(
                9108L, name, null, new BigDecimal("1000"), 1, List.of(),
                null, null, null, null, false, null, null, status)).id();
    }

    @Test
    void includesStatusAndSellerAndFiltersByStatus() {
        Long draft = create("관리목록 초안", "DRAFT");
        Long live = create("관리목록 판매중", "LIVE");

        var all = productService.listForManagement(null);
        assertThat(all).filteredOn(p -> p.id().equals(draft)).singleElement().satisfies(p -> {
            assertThat(p.status()).isEqualTo("DRAFT");
            assertThat(p.sellerId()).isEqualTo(1L);
            assertThat(p.sellerName()).isNotBlank();
        });
        assertThat(all).extracting(p -> p.id()).contains(draft, live);

        assertThat(productService.listForManagement(ProductStatus.DRAFT))
                .extracting(p -> p.id()).contains(draft).doesNotContain(live);
    }

    /** product.front#36 - 공개 판매자 정보는 법정 항목만. record 에 정산 계좌 필드 자체가 없다. */
    @Test
    void publicSellerInfoHasLegalFieldsOnly() {
        Long id = create("판매자정보 테스트", "LIVE");

        var info = productService.publicSellerInfoOf(id);

        assertThat(info.name()).isNotBlank();
        assertThat(info.businessRegistrationNo()).isNotBlank();
        assertThat(java.util.Arrays.stream(info.getClass().getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("settlementBank", "settlementAccount", "shippingOriginAddress");
    }

    @Autowired
    private ProductPolicyService productPolicyService;

    /** product.api#97 - 판매 기간이 끝난 상품의 SKU 는 주문 가격 확정에서 active=false, 최대 수량은 그대로 전달. */
    @Test
    void resolveReflectsSalePeriodAndMaxQuantity() {
        Long ended = create("판매 종료 상품", "LIVE");
        Long limited = create("수량 제한 상품", "LIVE");
        productPolicyService.replace(ended, new com.dh.product.dto.PolicyDtos.ProductPolicyRequest(
                null, null, null, null, null, null, null, null, null, null, null, null,
                java.time.LocalDateTime.now().minusDays(2), java.time.LocalDateTime.now().minusDays(1), null));
        productPolicyService.replace(limited, new com.dh.product.dto.PolicyDtos.ProductPolicyRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, 2));
        Long endedVariant = productService.getProduct(ended).variants().get(0).id();
        Long limitedVariant = productService.getProduct(limited).variants().get(0).id();

        var resolved = productService.resolveVariants(List.of(endedVariant, limitedVariant));

        assertThat(resolved).filteredOn(r -> r.variantId().equals(endedVariant)).singleElement()
                .satisfies(r -> assertThat(r.active()).isFalse());
        assertThat(resolved).filteredOn(r -> r.variantId().equals(limitedVariant)).singleElement()
                .satisfies(r -> {
                    assertThat(r.active()).isTrue();
                    assertThat(r.maxPurchaseQuantity()).isEqualTo(2);
                });
    }
}
