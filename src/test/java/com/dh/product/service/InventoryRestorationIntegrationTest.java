package com.dh.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dh.product.config.CacheNames;
import com.dh.product.domain.Category;
import com.dh.product.domain.Inventory;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductVariant;
import com.dh.product.dto.InventoryDtos.DeductItem;
import com.dh.product.dto.InventoryDtos.RestoreItem;
import com.dh.product.dto.InventoryDtos.InventoryBalanceResponse;
import com.dh.product.repository.CategoryRepository;
import com.dh.product.repository.InventoryRepository;
import com.dh.product.repository.InventoryTransactionRepository;
import com.dh.product.repository.ProductRepository;
import com.dh.product.repository.ProductVariantRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class InventoryRestorationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * 캐시는 이 테스트의 검증 대상이 아니다 - Redis 컨테이너를 띄우지 않으려고 로컬 캐시로 바꾼다.
     *
     * <p>다만 <b>애플리케이션이 쓰는 캐시 이름을 모두 선언해야 한다.</b> 이름을 지정한
     * {@link ConcurrentMapCacheManager}는 목록에 없는 캐시를 요청받으면 예외를 던지므로,
     * 재고 차감/복원이 메인 페이지 캐시를 무효화하는 순간(product.api#24) 캐시와 무관한
     * 이 테스트가 함께 깨진다. {@link CacheNames} 상수를 참조해 애플리케이션 쪽에 캐시가
     * 추가되면 여기도 같이 눈에 띄게 한다.
     */
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
    private InventoryDeductionService deductionService;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private ProductVariantRepository variantRepository;
    @Autowired
    private InventoryRepository inventoryRepository;
    @Autowired
    private InventoryTransactionRepository inventoryTransactionRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long variantId;

    @Autowired
    private com.dh.product.repository.ChannelRepository channelRepository;

    @BeforeEach
    void setUp() {
        inventoryTransactionRepository.deleteAll();
        inventoryRepository.deleteAll();
        variantRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        channelRepository.deleteAll();

        com.dh.product.domain.Channel channel = new com.dh.product.domain.Channel("종합몰", "posselect.com");
        channelRepository.save(channel);

        Category category = new Category();
        category.setName("테스트 카테고리");
        category.setChannel(channel);
        categoryRepository.save(category);

        Product product = new Product();
        product.setCategory(category);
        product.setName("테스트 상품");
        productRepository.save(product);

        ProductVariant variant = new ProductVariant(product, "SKU-TEST-1", new BigDecimal("10000.00"));
        variantRepository.save(variant);
        variantId = variant.getId();

        inventoryRepository.save(new Inventory(variant, 10));
    }

    private int committedQuantity() {
        return jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventories WHERE variant_id = ?", Integer.class, variantId);
    }

    private int restoreRowCount(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_transactions WHERE order_id = ? AND type = 'ORDER_RESTORE'",
                Integer.class, orderId);
    }

    private List<RestoreItem> threeUnitsRestore() {
        return List.of(new RestoreItem(variantId, 3));
    }

    private List<DeductItem> threeUnitsDeduct() {
        return List.of(new DeductItem(variantId, 3));
    }

    @Test
    @DisplayName("복원이 실제로 DB에 커밋된다")
    void 복원_정상_반영() {
        deductionService.deductForOrder(2001L, threeUnitsDeduct());
        assertThat(committedQuantity()).isEqualTo(7);

        List<InventoryBalanceResponse> result = deductionService.restoreForOrder(2001L, threeUnitsRestore());

        assertThat(committedQuantity()).isEqualTo(10);
        assertThat(restoreRowCount(2001L)).isEqualTo(1);
        assertThat(result).singleElement()
                .extracting(InventoryBalanceResponse::remainingQuantity).isEqualTo(10);
    }

    @Test
    @DisplayName("같은 주문으로 두 번 복원해도 재고는 한 번만 더해진다")
    void 멱등성_이중복원_방지() {
        deductionService.deductForOrder(2002L, threeUnitsDeduct());
        deductionService.restoreForOrder(2002L, threeUnitsRestore());
        List<InventoryBalanceResponse> retry = deductionService.restoreForOrder(2002L, threeUnitsRestore());

        assertThat(committedQuantity()).isEqualTo(10);
        assertThat(restoreRowCount(2002L)).isEqualTo(1);
        assertThat(retry).singleElement()
                .extracting(InventoryBalanceResponse::remainingQuantity).isEqualTo(10);
    }
}
