package com.dh.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.dao.OptimisticLockingFailureException;
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
import com.dh.product.repository.CategoryRepository;
import com.dh.product.repository.InventoryRepository;
import com.dh.product.repository.InventoryTransactionRepository;
import com.dh.product.repository.ProductRepository;
import com.dh.product.repository.ProductVariantRepository;
import com.dh.product.repository.SellerRepository;

/**
 * 서로 <b>다른</b> 주문끼리 같은 재고 행을 놓고 경쟁하는 상황의 통합 테스트 (product.api#35).
 *
 * <p>{@link InventoryDeductionIntegrationTest}는 같은 주문(orderId)의 중복 요청 멱등성만 본다.
 * 여기서 보는 건 선착순 한정수량처럼 여러 주문이 한 행에 몰리는 경우다. {@code @Version} 낙관적 락에
 * 기댄 read-modify-write 였을 때는 진 쪽이 {@code ObjectOptimisticLockingFailureException}을 던졌고,
 * {@code ApiExceptionHandler}가 그 타입을 몰라 매진 안내(409) 대신 500 이 나갔다.
 *
 * <p>같은 이유(posselect #211)로 결과는 서비스 반환값이 아니라 커밋된 행을 JdbcTemplate으로 읽어 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class InventoryConcurrentDeductionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withStartupTimeout(Duration.ofMinutes(3));

    /** 캐시는 검증 대상이 아니다 - 이유는 {@link InventoryDeductionIntegrationTest}의 같은 설정 주석 참고. */
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

    private static final int CONCURRENT_ORDERS = 20;

    /** 한 스레드가 낸 결과 - 성공, 매진, 그 밖의 예외를 구분해서 센다. */
    private enum Outcome { DEDUCTED, SOLD_OUT }

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
    @Autowired
    private com.dh.product.repository.ChannelRepository channelRepository;

    private Product product;
    private ExecutorService executor;

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

        product = new Product();
        product.setCategory(category);
        product.setName("선착순 한정수량 상품");
        product.setSeller(sellerRepository.findById(1L).orElseThrow());
        product.setStatus(ProductStatus.LIVE);
        productRepository.save(product);

        executor = Executors.newFixedThreadPool(CONCURRENT_ORDERS);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        // 한 테스트가 실패해도 남은 스레드가 다음 테스트의 setUp 과 같은 행을 놓고 다투지 않게 끝까지 기다린다.
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
    }

    private Long variantWithStock(String sku, int quantity) {
        ProductVariant variant = new ProductVariant(product, sku, new BigDecimal("10000.00"));
        variantRepository.save(variant);
        inventoryRepository.save(new Inventory(variant, quantity));
        return variant.getId();
    }

    private int committedQuantity(Long variantId) {
        return jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventories WHERE variant_id = ?", Integer.class, variantId);
    }

    private int activeDeductRowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_transactions WHERE type = 'ORDER_DEDUCT' AND reversed = false",
                Integer.class);
    }

    /** 모든 작업을 같은 순간에 출발시키고 결과를 모은다. 매진 외의 예외는 여기서 그대로 터져 테스트를 실패시킨다. */
    private List<Outcome> runTogether(List<Callable<Outcome>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (Callable<Outcome> task : tasks) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return task.call();
            }));
        }
        ready.await(10, TimeUnit.SECONDS);
        start.countDown();

        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> future : futures) {
            outcomes.add(future.get(30, TimeUnit.SECONDS));
        }
        return outcomes;
    }

    private Callable<Outcome> deduct(long orderId, List<DeductItem> items) {
        return () -> {
            try {
                deductionService.deductForOrder(orderId, items);
                return Outcome.DEDUCTED;
            } catch (IllegalStateException e) {
                // ApiExceptionHandler 가 409 로 내보내는 매진 응답. 다른 예외(특히 낙관적 락 실패)가
                // 새면 잡지 않고 테스트를 실패시킨다 - 그게 이 이슈의 원래 버그다.
                assertThat(e).hasMessageContaining("재고가 부족합니다");
                return Outcome.SOLD_OUT;
            }
        };
    }

    @Test
    @DisplayName("재고 1개를 서로 다른 주문 20개가 동시에 요청하면 1개만 성공하고 나머지는 매진 응답을 받는다")
    void 동시_주문_경쟁에서_정확히_하나만_성공한다() throws Exception {
        Long variantId = variantWithStock("SKU-LIMITED-1", 1);

        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_ORDERS; i++) {
            tasks.add(deduct(9000L + i, List.of(new DeductItem(variantId, 1))));
        }
        List<Outcome> outcomes = runTogether(tasks);

        assertThat(outcomes).filteredOn(o -> o == Outcome.DEDUCTED).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o == Outcome.SOLD_OUT).hasSize(CONCURRENT_ORDERS - 1);
        assertThat(committedQuantity(variantId)).isZero();
        assertThat(activeDeductRowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("재고가 넉넉하면 동시에 들어온 주문이 전부 성공하고 차감 합계가 정확하다")
    void 재고가_충분하면_동시_주문이_모두_성공한다() throws Exception {
        Long variantId = variantWithStock("SKU-AMPLE-1", 100);

        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_ORDERS; i++) {
            tasks.add(deduct(9100L + i, List.of(new DeductItem(variantId, 2))));
        }
        List<Outcome> outcomes = runTogether(tasks);

        assertThat(outcomes).containsOnly(Outcome.DEDUCTED);
        assertThat(committedQuantity(variantId)).isEqualTo(100 - CONCURRENT_ORDERS * 2);
        assertThat(activeDeductRowCount()).isEqualTo(CONCURRENT_ORDERS);
    }

    @Test
    @DisplayName("두 상품을 서로 반대 순서로 담은 주문들이 동시에 들어와도 교착 없이 전부 성공한다")
    void 항목_순서가_엇갈린_동시_주문도_모두_성공한다() throws Exception {
        Long first = variantWithStock("SKU-PAIR-A", 100);
        Long second = variantWithStock("SKU-PAIR-B", 100);

        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_ORDERS; i++) {
            List<DeductItem> items = i % 2 == 0
                    ? List.of(new DeductItem(first, 1), new DeductItem(second, 1))
                    : List.of(new DeductItem(second, 1), new DeductItem(first, 1));
            tasks.add(deduct(9200L + i, items));
        }
        List<Outcome> outcomes = runTogether(tasks);

        assertThat(outcomes).containsOnly(Outcome.DEDUCTED);
        assertThat(committedQuantity(first)).isEqualTo(100 - CONCURRENT_ORDERS);
        assertThat(committedQuantity(second)).isEqualTo(100 - CONCURRENT_ORDERS);
    }

    @Test
    @DisplayName("복원과 다른 주문의 차감이 겹쳐도 어느 쪽 변경도 사라지지 않는다")
    void 복원과_차감이_겹쳐도_갱신이_유실되지_않는다() throws Exception {
        // 복원은 여전히 @Version 에 기댄 read-modify-write 다. 차감이 버전을 올리지 않으면 복원이
        // "읽은 뒤 끼어든 차감"을 모르고 덮어써 재고가 실제보다 많아진다.
        for (int round = 0; round < 10; round++) {
            Long variantId = variantWithStock("SKU-RESTORE-" + round, 50);
            long restoredOrder = 9300L + round * 100;
            deductionService.deductForOrder(restoredOrder, List.of(new DeductItem(variantId, 5)));

            List<Callable<Outcome>> tasks = new ArrayList<>();
            tasks.add(() -> {
                try {
                    deductionService.restoreForOrder(restoredOrder, List.of(new RestoreItem(variantId, 5)));
                    return Outcome.DEDUCTED; // 복원이 반영됨
                } catch (OptimisticLockingFailureException e) {
                    return Outcome.SOLD_OUT; // 복원이 경합에서 져 롤백됨 - 호출자가 다시 시도한다
                }
            });
            for (int i = 1; i < CONCURRENT_ORDERS; i++) {
                tasks.add(deduct(restoredOrder + i, List.of(new DeductItem(variantId, 1))));
            }
            List<Outcome> outcomes = runTogether(tasks);

            int restored = outcomes.get(0) == Outcome.DEDUCTED ? 5 : 0;
            int deducted = (int) outcomes.subList(1, outcomes.size()).stream()
                    .filter(o -> o == Outcome.DEDUCTED).count();
            assertThat(deducted).isEqualTo(CONCURRENT_ORDERS - 1);
            assertThat(committedQuantity(variantId))
                    .as("round %d: 50 - 5 + 복원 %d - 차감 %d", round, restored, deducted)
                    .isEqualTo(50 - 5 + restored - deducted);
        }
    }
}
