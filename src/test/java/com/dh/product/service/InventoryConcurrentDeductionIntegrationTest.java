package com.dh.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.dh.product.dto.InventoryDtos.InventoryBalanceResponse;
import com.dh.product.repository.CategoryRepository;
import com.dh.product.repository.InventoryRepository;
import com.dh.product.repository.InventoryTransactionRepository;
import com.dh.product.repository.ProductRepository;
import com.dh.product.repository.ProductVariantRepository;

/**
 * 선착순 한정수량 프로모션을 대비한 "서로 다른 주문끼리의" 동시성 테스트 (product.api#35).
 *
 * <p>{@link InventoryDeductionIntegrationTest}는 같은 주문(orderId)의 중복 요청 멱등성만 본다.
 * 이 테스트는 <b>다른</b> 주문 여러 개가 재고 1개짜리 행을 동시에 놓고 경쟁하는 상황을 재현한다 -
 * 기존 {@code @Version} 낙관적 락 방식이었다면 진 쪽이 {@code ObjectOptimisticLockingFailureException}을
 * 던지고 {@code ApiExceptionHandler}가 그 타입을 처리하지 않아 raw 500이 됐던 지점이다.
 *
 * <p>여기서도 커밋된 행을 JdbcTemplate으로 직접 읽어 검증한다 - 동시성 결과는 서비스 반환값이나
 * 영속성 컨텍스트만으로는 신뢰할 수 없다(같은 이유: posselect #211).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class InventoryConcurrentDeductionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** 캐시 검증 대상이 아니다 - Redis 컨테이너 없이 로컬 캐시로 대체(InventoryDeductionIntegrationTest와 동일 이유). */
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
    @Autowired
    private com.dh.product.repository.ChannelRepository channelRepository;

    private Long variantId;
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

        Product product = new Product();
        product.setCategory(category);
        product.setName("선착순 한정수량 상품");
        productRepository.save(product);

        ProductVariant variant = new ProductVariant(product, "SKU-LIMITED-1", new BigDecimal("10000.00"));
        variantRepository.save(variant);
        variantId = variant.getId();

        // 딱 1개 - 동시에 들어오는 CONCURRENT_ORDERS개의 서로 다른 주문 중 정확히 하나만 성공해야 한다.
        inventoryRepository.save(new Inventory(variant, 1));

        executor = Executors.newFixedThreadPool(CONCURRENT_ORDERS);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private int committedQuantity() {
        return jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventories WHERE variant_id = ?", Integer.class, variantId);
    }

    private int deductRowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_transactions WHERE type = 'ORDER_DEDUCT'", Integer.class);
    }

    @Test
    @DisplayName("재고 1개를 서로 다른 주문 N개가 동시에 요청하면 정확히 1개만 성공하고 나머지는 깨끗한 재고부족 예외를 받는다")
    void 동시_주문_경쟁에서_정확히_하나만_성공한다() throws Exception {
        CountDownLatch ready = new CountDownLatch(CONCURRENT_ORDERS);
        CountDownLatch start = new CountDownLatch(1);

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_ORDERS; i++) {
            long orderId = 9000L + i;
            tasks.add(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                try {
                    List<InventoryBalanceResponse> result =
                            deductionService.deductForOrder(orderId, List.of(new DeductItem(variantId, 1)));
                    assertThat(result).singleElement()
                            .extracting(InventoryBalanceResponse::remainingQuantity).isEqualTo(0);
                    return true; // 성공(재고 확보)
                } catch (IllegalStateException e) {
                    // "재고 부족" - InventoryDeductor#deductAtomically가 원자적 UPDATE의 영향받은
                    // 행이 0일 때 던지는, ApiExceptionHandler가 409로 매핑하는 바로 그 예외다.
                    // 여기서 잡히지 않는 다른 예외(특히 ObjectOptimisticLockingFailureException)가
                    // 새면 이 테스트는 실패해야 한다 - 그게 이 이슈의 원래 버그였다.
                    assertThat(e).hasMessageContaining("재고가 부족합니다");
                    return false; // 매진으로 인한 실패(정상적인 비즈니스 결과)
                }
            });
        }

        List<Future<Boolean>> futures = new ArrayList<>();
        for (Callable<Boolean> task : tasks) {
            futures.add(executor.submit(task));
        }
        // 모든 스레드가 대기 상태에 들어간 뒤 동시에 출발시켜 경쟁을 최대화한다.
        ready.await(10, TimeUnit.SECONDS);
        start.countDown();

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        for (Future<Boolean> future : futures) {
            if (future.get(30, TimeUnit.SECONDS)) {
                succeeded.incrementAndGet();
            } else {
                soldOut.incrementAndGet();
            }
        }

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(soldOut.get()).isEqualTo(CONCURRENT_ORDERS - 1);

        // 커밋된 DB 상태로 최종 확인 - 절대 음수가 될 수 없고(V3 CHECK 제약), 정확히 0이어야 한다.
        assertThat(committedQuantity()).isZero();
        assertThat(deductRowCount()).isEqualTo(1);
    }
}
