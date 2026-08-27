package com.dh.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.BeforeEach;
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

import com.dh.product.config.CacheNames;
import com.dh.product.domain.Category;
import com.dh.product.domain.Channel;
import com.dh.product.domain.Product;
import com.dh.product.domain.WishlistItem;
import com.dh.product.repository.CategoryRepository;
import com.dh.product.repository.ChannelRepository;
import com.dh.product.repository.ProductRepository;
import com.dh.product.repository.WishlistRepository;

/**
 * V6가 wishlist_items.product_id FK에 ON DELETE CASCADE를 빠뜨려서, 찜된 상품을
 * 관리자가 삭제하면 FK 위반으로 실패하던 문제(product.api#52)의 회귀 테스트.
 * V12__wishlist_cascade_delete.sql이 이 제약을 CASCADE로 바꾼다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ProductDeleteWishlistCascadeIntegrationTest {

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
    private ProductService productService;
    @Autowired
    private ChannelRepository channelRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private WishlistRepository wishlistRepository;

    private Long productId;

    @BeforeEach
    void setUp() {
        wishlistRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        channelRepository.deleteAll();

        Channel channel = new Channel("종합몰", "posselect.com");
        channelRepository.save(channel);

        Category category = new Category();
        category.setName("테스트 카테고리");
        category.setChannel(channel);
        categoryRepository.save(category);

        Product product = new Product();
        product.setCategory(category);
        product.setName("테스트 상품");
        productRepository.save(product);
        productId = product.getId();

        wishlistRepository.save(WishlistItem.builder().userId("user-1").product(product).build());
    }

    @Test
    @DisplayName("찜된 상품도 FK 위반 없이 삭제된다")
    void 찜된_상품_삭제_성공() {
        assertThatCode(() -> productService.deleteProduct(productId)).doesNotThrowAnyException();

        assertThat(productRepository.findById(productId)).isEmpty();
        assertThat(wishlistRepository.findByUserIdAndProductId("user-1", productId)).isEmpty();
    }
}
