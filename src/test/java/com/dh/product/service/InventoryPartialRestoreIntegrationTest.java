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
import com.dh.product.dto.InventoryDtos.PartialRestoreItem;
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
/**
 * 상품별 부분 복원(gateway#320). 고객이 주문의 일부 상품만 취소·반품하면 그 수량만 재고에 되돌린다.
 *
 * <p>지켜야 할 것: (1) 같은 클레임 키는 몇 번을 불러도 한 번만 더한다 (2) 이 주문이 실제로 뺀 수량보다 많이
 * 되돌리지 않는다 (3) 부분 복원 뒤 주문 전체 복원(결제 보상·전액 환불)이 오면 남은 수량만 되돌린다 —
 * 전체를 다시 더하면 재고가 생긴다.
 */
class InventoryPartialRestoreIntegrationTest {

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

    private List<DeductItem> deduct(int qty) {
        return List.of(new DeductItem(variantId, qty));
    }

    private List<PartialRestoreItem> partial(int qty) {
        return List.of(new PartialRestoreItem(variantId, qty));
    }

    @Test
    @DisplayName("일부 수량만 되돌린다")
    void 부분_복원() {
        deductionService.deductForOrder(3001L, deduct(5));
        assertThat(committedQuantity()).isEqualTo(5);

        List<InventoryBalanceResponse> result = deductionService.restorePartial(3001L, "claim-1", partial(2));

        assertThat(committedQuantity()).isEqualTo(7);
        assertThat(result).singleElement().extracting(InventoryBalanceResponse::remainingQuantity).isEqualTo(7);
    }

    @Test
    @DisplayName("같은 클레임 키로 다시 불러도 한 번만 더한다")
    void 같은_클레임_재시도() {
        deductionService.deductForOrder(3002L, deduct(5));
        deductionService.restorePartial(3002L, "claim-2", partial(2));
        deductionService.restorePartial(3002L, "claim-2", partial(2));

        assertThat(committedQuantity()).isEqualTo(7);
    }

    @Test
    @DisplayName("다른 클레임은 각각 더하되, 뺀 수량을 넘을 수는 없다")
    void 누적_상한() {
        deductionService.deductForOrder(3003L, deduct(5));
        deductionService.restorePartial(3003L, "claim-3a", partial(3));
        deductionService.restorePartial(3003L, "claim-3b", partial(2));
        assertThat(committedQuantity()).isEqualTo(10);

        assertThatThrownBy(() -> deductionService.restorePartial(3003L, "claim-3c", partial(1)))
                .isInstanceOf(PartialRestoreRejectedException.class);
        assertThat(committedQuantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("차감된 적 없는 주문은 거부한다 — 없던 재고를 만들지 않는다")
    void 차감_없는_주문() {
        assertThatThrownBy(() -> deductionService.restorePartial(3004L, "claim-4", partial(1)))
                .isInstanceOf(PartialRestoreRejectedException.class);
        assertThat(committedQuantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("부분 복원 뒤 주문 전체 복원은 남은 수량만 되돌린다")
    void 부분_뒤_전체() {
        deductionService.deductForOrder(3005L, deduct(5));
        deductionService.restorePartial(3005L, "claim-5", partial(2));
        assertThat(committedQuantity()).isEqualTo(7);

        // order.api 의 전체 복원은 주문 수량 그대로 보낸다. 장부가 남은 3개만 되돌려야 한다.
        deductionService.restoreForOrder(3005L, List.of(new RestoreItem(variantId, 5)));

        assertThat(committedQuantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("전체 복원된 주문에는 부분 복원을 받지 않는다")
    void 전체_뒤_부분() {
        deductionService.deductForOrder(3006L, deduct(5));
        deductionService.restoreForOrder(3006L, List.of(new RestoreItem(variantId, 5)));

        assertThatThrownBy(() -> deductionService.restorePartial(3006L, "claim-6", partial(1)))
                .isInstanceOf(PartialRestoreRejectedException.class);
        assertThat(committedQuantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("교환 발송분 차감: 같은 클레임 키는 한 번만, 주문 차감과 별개라 같은 주문·같은 재고여도 뺀다")
    void 교환_차감() {
        deductionService.deductForOrder(3007L, deduct(2));
        assertThat(committedQuantity()).isEqualTo(8);

        deductionService.deductForClaim(3007L, "claim-7-out", deduct(1));
        deductionService.deductForClaim(3007L, "claim-7-out", deduct(1));
        assertThat(committedQuantity()).isEqualTo(7);

        // 회수품 복원(부분 복원)은 주문 차감 기준이라 교환 차감과 섞이지 않는다.
        deductionService.restorePartial(3007L, "claim-7", partial(1));
        assertThat(committedQuantity()).isEqualTo(8);
    }

    @Test
    @DisplayName("교환 발송분이 재고보다 많으면 거부하고 아무것도 빼지 않는다")
    void 교환_재고_부족() {
        assertThatThrownBy(() -> deductionService.deductForClaim(3008L, "claim-8-out", deduct(11)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(committedQuantity()).isEqualTo(10);
    }
}
