package com.dh.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
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
import org.springframework.data.domain.Page;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.dh.product.config.CacheNames;
import com.dh.product.dto.ProductDtos.ProductCreateRequest;
import com.dh.product.dto.ProductDtos.ProductSummaryResponse;

/**
 * product.api#107 - 공개 목록의 page/size. 상태·카테고리·검색어 조건과 쪽 나누기가 전부 쿼리에서
 * 일어나야 total 이 맞으므로(조회 후 걸러내면 쪽이 덜 찬다) 실제 DB 로 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ProductListPagingIntegrationTest {

    /** 신선식품(소분류). 부모는 식품(9003) - V8 시드. */
    private static final Long CATEGORY_ID = 9108L;
    private static final Long FOOD_PARENT_CATEGORY_ID = 9003L;

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

    @Autowired
    private ProductService productService;

    private Long create(String name, String status) {
        return productService.createProduct(new ProductCreateRequest(
                CATEGORY_ID, name, null, new BigDecimal("1000"), 1, List.of(),
                null, null, null, null, false, null, null, status)).id();
    }

    @Test
    void pagesAreDisjointNewestFirstAndTotalCountsOnlyMatches() {
        List<Long> live = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            live.add(create("쪽나눔A 상품 " + i, "LIVE"));
        }
        Long draft = create("쪽나눔A 초안", "DRAFT");

        Page<ProductSummaryResponse> first = productService.listProductsPage(null, "쪽나눔A", false, 0, 2);
        Page<ProductSummaryResponse> second = productService.listProductsPage(null, "쪽나눔A", false, 1, 2);
        Page<ProductSummaryResponse> last = productService.listProductsPage(null, "쪽나눔A", false, 2, 2);

        assertThat(first.getTotalElements()).as("DRAFT 는 total 에도 안 잡힌다").isEqualTo(5);
        assertThat(first.getContent()).extracting(ProductSummaryResponse::id)
                .containsExactly(live.get(4), live.get(3));
        assertThat(second.getContent()).extracting(ProductSummaryResponse::id)
                .containsExactly(live.get(2), live.get(1));
        assertThat(last.getContent()).extracting(ProductSummaryResponse::id).containsExactly(live.get(0));

        Page<ProductSummaryResponse> staff = productService.listProductsPage(null, "쪽나눔A", true, 0, 10);
        assertThat(staff.getTotalElements()).isEqualTo(6);
        assertThat(staff.getContent()).extracting(ProductSummaryResponse::id).contains(draft);
    }

    /** 소분류에 달린 상품이 대분류로 조회돼야 한다(product.api#102) - 쪽 나눈 경로에서도 같다. */
    @Test
    void categoryFilterIncludesDescendants() {
        Long id = create("쪽나눔B 상품", "LIVE");
        Page<ProductSummaryResponse> page = productService.listProductsPage(FOOD_PARENT_CATEGORY_ID, "쪽나눔B", false, 0, 10);

        assertThat(page.getContent()).extracting(ProductSummaryResponse::id).containsExactly(id);
    }

    /** 검색어의 % 와 _ 는 글자 그대로다 - LIKE 와일드카드로 새면 "%" 검색이 전 상품을 돌려준다. */
    @Test
    void wildcardCharactersInQueryAreLiteral() {
        create("쪽나눔C 상품", "LIVE");

        assertThat(productService.listProductsPage(null, "쪽나눔C%상품", false, 0, 10).getTotalElements()).isZero();
        assertThat(productService.listProductsPage(null, "쪽나눔c 상품", false, 0, 10).getTotalElements())
                .as("대소문자 무시는 기존 목록과 같다").isEqualTo(1);
    }
}
