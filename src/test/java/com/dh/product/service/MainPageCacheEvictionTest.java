package com.dh.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

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
import org.testcontainers.utility.DockerImageName;

import com.dh.product.config.CacheNames;
import com.dh.product.domain.Category;
import com.dh.product.domain.Inventory;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductVariant;
import com.dh.product.dto.InventoryDtos.DeductItem;
import com.dh.product.dto.ProductDtos.ProductCreateRequest;
import com.dh.product.repository.CategoryRepository;
import com.dh.product.repository.ChannelRepository;
import com.dh.product.repository.InventoryRepository;
import com.dh.product.repository.InventoryTransactionRepository;
import com.dh.product.repository.ProductRepository;
import com.dh.product.repository.ProductVariantRepository;

/**
 * 메인 페이지 캐시 무효화 통합 테스트 (product.api#24).
 *
 * <p><b>왜 이 테스트가 필요한가.</b> 메인 페이지 캐시 3종을 무효화하는 코드가 저장소에 아예 없어,
 * 가격 변경·품절 처리가 TTL(5~10분) 만료 전까지 메인에 반영되지 않았다. 스테일 캐시는 예외도 로그도
 * 남기지 않고 200과 함께 옛 값을 응답하므로, 코드를 눈으로 봐서는 빠진 것을 알아채기 어렵다.
 *
 * <p><b>왜 단위 테스트가 아닌가.</b> {@code @CacheEvict}는 스프링 프록시를 통해서만 동작한다.
 * 애노테이션을 붙였다는 사실과 실제로 무효화가 일어난다는 사실은 별개이고(자기 호출이면 안 돈다),
 * 목으로는 그 층이 재현되지 않는다. 그래서 실제 컨텍스트를 띄우고 <b>CacheManager에 남은 엔트리</b>를
 * 직접 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class MainPageCacheEvictionTest {

    @Container
    @ServiceConnection
    // V15(product.api#46)부터 Flyway 히스토리에 vector 확장이 포함돼, 확장 없는 stock 이미지로는
    // 이 테스트 자체와 무관하게 Flyway 마이그레이션 단계에서 컨텍스트 부팅이 실패한다.
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 컨테이너를 띄우지 않으려고 로컬 캐시로 바꾼다. 애플리케이션이 쓰는 캐시 이름을 모두 선언한다. */
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

    @Autowired private MainPageService mainPageService;
    @Autowired private ProductService productService;
    @Autowired private InventoryDeductionService deductionService;
    @Autowired private CacheManager cacheManager;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private InventoryTransactionRepository inventoryTransactionRepository;
    @Autowired private ChannelRepository channelRepository;

    private Long categoryId;
    private Long variantId;

    @BeforeEach
    void setUp() {
        inventoryTransactionRepository.deleteAll();
        inventoryRepository.deleteAll();
        variantRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        channelRepository.deleteAll();

        com.dh.product.domain.Channel channel =
                new com.dh.product.domain.Channel("종합몰", "posselect.com");
        channelRepository.save(channel);

        Category category = new Category();
        category.setName("테스트 카테고리");
        category.setChannel(channel);
        categoryRepository.save(category);
        categoryId = category.getId();

        Product product = new Product();
        product.setCategory(category);
        product.setName("테스트 상품");
        productRepository.save(product);

        ProductVariant variant = new ProductVariant(product, "SKU-TEST-1", new BigDecimal("10000.00"));
        variantRepository.save(variant);
        variantId = variant.getId();

        inventoryRepository.save(new Inventory(variant, 100));

        clearAllMainCaches();
    }

    private void clearAllMainCaches() {
        for (String name : List.of(CacheNames.MAIN_BEST, CacheNames.MAIN_NEW, CacheNames.MAIN_BY_CATEGORY)) {
            java.util.Objects.requireNonNull(cacheManager.getCache(name)).clear();
        }
    }

    /** 메인 페이지 캐시 3종을 모두 채운다. */
    private void warmMainCaches() {
        mainPageService.getBestProducts(10);
        mainPageService.getNewProducts(10);
        mainPageService.getProductsByCategory();
        assertThat(cachedCount()).as("사전 조건: 캐시 3종이 채워져 있어야 한다").isEqualTo(3);
    }

    /** 캐시에 값이 남아 있는 캐시의 개수. */
    private long cachedCount() {
        return List.of(CacheNames.MAIN_BEST, CacheNames.MAIN_NEW, CacheNames.MAIN_BY_CATEGORY).stream()
                .map(cacheManager::getCache)
                .map(java.util.Objects::requireNonNull)
                .map(c -> ((ConcurrentMapCacheManager) cacheManager).getCache(c.getName()))
                .filter(c -> !((java.util.concurrent.ConcurrentMap<?, ?>) c.getNativeCache()).isEmpty())
                .count();
    }

    @Test
    @DisplayName("재고 차감이 메인 페이지 캐시를 무효화한다 — 주문마다 재고가 바뀌므로 이게 가장 잦은 경로다")
    void deductInventoryEvictsMainCaches() {
        warmMainCaches();

        deductionService.deductForOrder(1001L, List.of(new DeductItem(variantId, 3)));

        assertThat(cachedCount())
                .as("재고가 줄었는데 메인 캐시가 남아 있으면 품절 상품이 구매 가능한 것처럼 보인다")
                .isZero();
    }

    @Test
    @DisplayName("재고 복원이 메인 페이지 캐시를 무효화한다")
    void restoreInventoryEvictsMainCaches() {
        deductionService.deductForOrder(1002L, List.of(new DeductItem(variantId, 3)));
        warmMainCaches();

        deductionService.restoreForOrder(1002L,
                List.of(new com.dh.product.dto.InventoryDtos.RestoreItem(variantId, 3)));

        assertThat(cachedCount()).isZero();
    }

    @Test
    @DisplayName("상품 등록이 메인 페이지 캐시를 무효화한다 — 신상품이 즉시 노출되어야 한다")
    void createProductEvictsMainCaches() {
        warmMainCaches();

        productService.createProduct(new ProductCreateRequest(
                categoryId, "새 상품", "설명", new BigDecimal("5000"), 10, List.of(),
                null, null, null, null, false, null));

        assertThat(cachedCount()).isZero();
    }

    @Test
    @DisplayName("상품 삭제가 메인 페이지 캐시를 무효화한다")
    void deleteProductEvictsMainCaches() {
        Long newProductId = productService.createProduct(new ProductCreateRequest(
                categoryId, "지울 상품", "설명", new BigDecimal("5000"), 10, List.of(),
                null, null, null, null, false, null)).id();
        warmMainCaches();

        productService.deleteProduct(newProductId);

        assertThat(cachedCount())
                .as("삭제된 상품이 메인에 계속 보이면 클릭 시 404가 난다")
                .isZero();
    }
}
