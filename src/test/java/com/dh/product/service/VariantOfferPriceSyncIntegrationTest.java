package com.dh.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
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
import com.dh.product.dto.ProductDtos.ProductCreateRequest;
import com.dh.product.dto.ProductDtos.UpdateVariantRequest;

/**
 * product.api#77 - 쇼핑몰 대표가는 SKU 가격이 아니라 오퍼 가격에서 나온다. 관리자 SKU 수정
 * ({@code PUT /api/products/{id}/variants/{variantId}})이 SKU 가격만 바꾸고 오퍼를 그대로 두면
 * "가격을 바꿨는데 쇼핑몰은 그대로"가 된다. 실제 DB 로 목록 대표가까지 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class VariantOfferPriceSyncIntegrationTest {

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
                    CacheNames.PRODUCT, CacheNames.MAIN_BEST, CacheNames.MAIN_NEW, CacheNames.MAIN_BY_CATEGORY);
        }
    }

    /** V8 이 심은 비제한(인허가 불필요) 카테고리가 아니어도 된다 - 여기선 검수를 거치지 않는다. */
    private static final Long CATEGORY_ID = 9108L;

    @Autowired
    private ProductService productService;

    @Test
    void updatingVariantPriceUpdatesShopPrice() {
        var product = productService.createProduct(new ProductCreateRequest(
                CATEGORY_ID, "가격 동기화 테스트", null, new BigDecimal("10000"), 5,
                List.of(), null, null, null, null, false, null, null, "LIVE"));
        var variant = product.variants().get(0);

        productService.updateVariant(product.id(), variant.id(),
                new UpdateVariantRequest(variant.sku(), new BigDecimal("7000"), 5, true));

        assertThat(productService.listProducts(CATEGORY_ID, "가격 동기화 테스트"))
                .singleElement()
                .satisfies(p -> assertThat(p.price()).isEqualByComparingTo("7000"));
    }
}
