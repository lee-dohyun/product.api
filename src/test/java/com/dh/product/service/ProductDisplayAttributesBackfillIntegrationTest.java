package com.dh.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

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

import com.dh.product.config.CacheNames;

/**
 * V13__product_display_attributes.sql의 데모 상품 백필이 V8 시드(product.api#27)에
 * 실제로 적용되는지 검증한다. Flyway가 테스트 컨테이너 DB에도 V1~V13을 전부 실행하므로
 * V8이 만든 128개 데모 상품을 대상으로 실측할 수 있다(product.api#28).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ProductDisplayAttributesBackfillIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("데모 상품 전량이 브랜드/평점/정가를 채웠고 정가가 판매가보다 높다")
    void 데모_상품_노출속성_백필_확인() {
        Integer demoCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM products WHERE is_demo", Integer.class);
        assertThat(demoCount).isGreaterThan(0);

        // description에 '의'가 있는 V8 생성 상품(옛 "테스트 상품" id=1은 제외)은 brand가 채워져야 한다
        Integer missingBrand = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM products WHERE is_demo AND position('의' IN description) > 0 AND brand IS NULL",
                Integer.class);
        assertThat(missingBrand).isZero();

        Integer outOfRangeRating = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM products WHERE is_demo AND (rating_avg < 3.5 OR rating_avg > 5.0)",
                Integer.class);
        assertThat(outOfRangeRating).isZero();

        Integer missingListPrice = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM products p WHERE p.is_demo AND p.list_price IS NULL "
                        + "AND EXISTS (SELECT 1 FROM product_variants v WHERE v.product_id = p.id AND v.active)",
                Integer.class);
        assertThat(missingListPrice)
                .as("variant가 있는 데모 상품은 list_price가 채워져야 한다")
                .isZero();

        // 정가가 판매가(최저 활성 variant가)보다 낮은 상품이 있으면 "할인율이 음수"가 된다
        Integer priceInverted = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM products p "
                        + "JOIN (SELECT product_id, MIN(price) AS min_price FROM product_variants WHERE active GROUP BY product_id) sub "
                        + "  ON sub.product_id = p.id "
                        + "WHERE p.is_demo AND p.list_price IS NOT NULL AND p.list_price < sub.min_price",
                Integer.class);
        assertThat(priceInverted).isZero();

        BigDecimal sampleListPrice = jdbcTemplate.queryForObject(
                "SELECT list_price FROM products WHERE id = 9001", BigDecimal.class);
        assertThat(sampleListPrice).isNotNull();
    }
}
