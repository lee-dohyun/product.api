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
 * 재고 차감 멱등성 통합 테스트 (Redmine posselect #211).
 *
 * <p><b>왜 단위 테스트가 아니라 이건가.</b> 1차 시도(dac4437)는 Mockito 단위 테스트 5건을 전부
 * 통과했는데도 운영에서 "이력은 남는데 재고 UPDATE가 사라지는" 회귀를 냈다. 원인이 트랜잭션
 * 전파와 Hibernate flush 시점이었기 때문이다 — 목으로는 재현되지 않는 층이다. 그래서 이 테스트는
 * 서비스가 반환한 값이 아니라 <b>실제 Postgres에 커밋된 행</b>을 JdbcTemplate으로 직접 읽어
 * 검증한다. 영속성 컨텍스트에 남은 값을 보면 같은 함정에 다시 빠진다.
 *
 * <p>Flyway V1~V3가 컨테이너에 그대로 적용되므로 부분 유니크 인덱스와 음수 재고 CHECK도 함께
 * 검증된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class InventoryDeductionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** 캐시는 이 테스트의 검증 대상이 아니다 - Redis 컨테이너를 띄우지 않으려고 로컬 캐시로 바꾼다. */
    @TestConfiguration
    static class LocalCacheConfig {
        @Bean
        @Primary
        CacheManager testCacheManager() {
            return new ConcurrentMapCacheManager("product");
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

    /** 서비스가 만든 영속성 컨텍스트를 우회해 커밋된 값만 읽는다. */
    private int committedQuantity() {
        return jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventories WHERE variant_id = ?", Integer.class, variantId);
    }

    private int deductRowCount(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_transactions WHERE order_id = ? AND type = 'ORDER_DEDUCT'",
                Integer.class, orderId);
    }

    private List<DeductItem> threeUnits() {
        return List.of(new DeductItem(variantId, 3));
    }

    @Test
    @DisplayName("차감이 실제로 DB에 커밋된다 - 1차 시도가 놓친 회귀")
    void 차감은_DB에_반영된다() {
        List<InventoryBalanceResponse> result = deductionService.deductForOrder(1001L, threeUnits());

        assertThat(committedQuantity()).isEqualTo(7);
        assertThat(deductRowCount(1001L)).isEqualTo(1);
        assertThat(result).singleElement()
                .extracting(InventoryBalanceResponse::remainingQuantity).isEqualTo(7);
    }

    @Test
    @DisplayName("같은 주문으로 두 번 호출해도 재고는 한 번만 빠진다")
    void 같은_주문의_재시도는_이중차감되지_않는다() {
        deductionService.deductForOrder(1002L, threeUnits());
        List<InventoryBalanceResponse> retry = deductionService.deductForOrder(1002L, threeUnits());

        assertThat(committedQuantity()).isEqualTo(7);
        assertThat(deductRowCount(1002L)).isEqualTo(1);
        // 재시도 응답도 실제 잔고를 그대로 알려줘야 order.api가 주문을 확정할 수 있다.
        assertThat(retry).singleElement()
                .extracting(InventoryBalanceResponse::remainingQuantity).isEqualTo(7);
    }

    @Test
    @DisplayName("주문이 다르면 각각 차감된다 - 멱등성이 정상 차감까지 막지는 않는다")
    void 다른_주문은_각각_차감된다() {
        deductionService.deductForOrder(1003L, threeUnits());
        deductionService.deductForOrder(1004L, threeUnits());

        assertThat(committedQuantity()).isEqualTo(4);
    }

    @Test
    @DisplayName("재고가 부족하면 예외가 나고 아무것도 커밋되지 않는다")
    void 재고_부족은_전체_롤백된다() {
        assertThatThrownBy(() -> deductionService.deductForOrder(1005L, List.of(new DeductItem(variantId, 11))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(committedQuantity()).isEqualTo(10);
        assertThat(deductRowCount(1005L)).isZero();
    }

    @Test
    @DisplayName("응용 레벨 확인을 우회해도 유니크 인덱스가 이중 차감을 막는다")
    void DB_제약이_최후의_방어선이다() {
        deductionService.deductForOrder(1006L, threeUnits());
        Long inventoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM inventories WHERE variant_id = ?", Long.class, variantId);

        // 동시 요청이 둘 다 이력 확인을 통과한 상황을 직접 재현한다.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO inventory_transactions "
                        + "(inventory_id, type, quantity_change, balance_after, order_id, created_at) "
                        + "VALUES (?, 'ORDER_DEDUCT', -3, 4, 1006, now())",
                inventoryId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(deductRowCount(1006L)).isEqualTo(1);
    }
}
