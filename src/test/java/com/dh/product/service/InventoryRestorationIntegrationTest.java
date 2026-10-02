package com.dh.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
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
import org.testcontainers.utility.DockerImageName;

import com.dh.product.config.CacheNames;
import com.dh.product.domain.Category;
import com.dh.product.domain.Inventory;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductStatus;
import com.dh.product.domain.ProductVariant;
import com.dh.product.dto.InventoryDtos.DeductItem;
import com.dh.product.dto.InventoryDtos.RestoreItem;
import com.dh.product.dto.InventoryDtos.InventoryBalanceResponse;
import com.dh.product.repository.CategoryRepository;
import com.dh.product.repository.InventoryRepository;
import com.dh.product.repository.InventoryTransactionRepository;
import com.dh.product.repository.ProductRepository;
import com.dh.product.repository.SellerRepository;
import com.dh.product.repository.ProductVariantRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class InventoryRestorationIntegrationTest {

    @Container
    @ServiceConnection
    // V15(product.api#46)부터 Flyway 히스토리에 vector 확장이 포함돼, 확장 없는 stock 이미지로는
    // 이 테스트 자체와 무관하게 Flyway 마이그레이션 단계에서 컨텍스트 부팅이 실패한다.
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withStartupTimeout(Duration.ofMinutes(3));

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
    private SellerRepository sellerRepository;
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
        // products.seller_id/status 는 V14 부터 NOT NULL 이다(product.api#29). 자사 판매자(id=1)는
        // 같은 마이그레이션이 시드하므로 여기서 만들지 않고 조회해서 붙인다.
        product.setSeller(sellerRepository.findById(1L).orElseThrow());
        product.setStatus(ProductStatus.LIVE);
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

    /**
     * product.api#115 - 결제 확정 실패로 보상 복원이 나간 주문을 고객이 다시 결제하는 경우다.
     * 차감 판정이 "차감 이력이 있는가"뿐이면 두 번째 차감이 건너뛰어져 재고가 안 빠진 채 팔린다.
     */
    @Test
    @DisplayName("복원된 주문을 다시 차감하면 재고가 다시 빠진다")
    void 복원_뒤_재차감() {
        deductionService.deductForOrder(2003L, threeUnitsDeduct());
        deductionService.restoreForOrder(2003L, threeUnitsRestore());
        assertThat(committedQuantity()).isEqualTo(10);

        deductionService.deductForOrder(2003L, threeUnitsDeduct());

        assertThat(committedQuantity()).isEqualTo(7);
    }

    @Test
    @DisplayName("복원 뒤 재차감한 주문을 다시 복원(환불)하면 재고가 돌아온다")
    void 재차감_뒤_재복원() {
        deductionService.deductForOrder(2004L, threeUnitsDeduct());
        deductionService.restoreForOrder(2004L, threeUnitsRestore());
        deductionService.deductForOrder(2004L, threeUnitsDeduct());

        deductionService.restoreForOrder(2004L, threeUnitsRestore());
        deductionService.restoreForOrder(2004L, threeUnitsRestore());

        assertThat(committedQuantity()).isEqualTo(10);
        assertThat(restoreRowCount(2004L)).isEqualTo(2);
    }

    @Test
    @DisplayName("차감된 적 없는 주문의 복원은 재고를 늘리지 않는다")
    void 차감_없는_복원은_무시() {
        deductionService.restoreForOrder(2005L, threeUnitsRestore());

        assertThat(committedQuantity()).isEqualTo(10);
        assertThat(restoreRowCount(2005L)).isZero();
    }

    @Test
    @DisplayName("같은 주문의 복원이 동시에 들어와도 한 번만 더해진다")
    void 동시_복원은_한_번만() throws Exception {
        deductionService.deductForOrder(2006L, threeUnitsDeduct());
        int threads = 6;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var start = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return deductionService.restoreForOrder(2006L, threeUnitsRestore());
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(committedQuantity()).isEqualTo(10);
        assertThat(restoreRowCount(2006L)).isEqualTo(1);
    }

    /** V24 가 좁힌 유니크 인덱스(되돌려지지 않은 차감)가 재차감 경로에서도 최종 방어로 남는지 본다. */
    @Test
    @DisplayName("복원된 주문의 재차감이 동시에 들어와도 한 번만 빠진다")
    void 동시_재차감은_한_번만() throws Exception {
        deductionService.deductForOrder(2007L, threeUnitsDeduct());
        deductionService.restoreForOrder(2007L, threeUnitsRestore());
        int threads = 6;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var start = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return deductionService.deductForOrder(2007L, threeUnitsDeduct());
            }));
        }
        start.countDown();
        int failures = 0;
        for (var f : futures) {
            try {
                f.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                // 재고 행의 낙관적 잠금(@Version)에 진 요청은 예외로 끝난다 - 차감은 반영되지 않는다.
                failures++;
            }
        }
        pool.shutdown();

        assertThat(failures).isLessThan(threads);
        assertThat(committedQuantity()).isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_transactions WHERE order_id = 2007 AND type = 'ORDER_DEDUCT'"
                        + " AND reversed = false", Integer.class)).isEqualTo(1);
    }
}
