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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.dh.product.config.CacheNames;
import com.dh.product.domain.Category;
import com.dh.product.domain.Channel;
import com.dh.product.domain.SellerStatus;
import com.dh.product.domain.SellerType;
import com.dh.product.dto.ProductDtos.ProductCreateRequest;
import com.dh.product.dto.ProductDtos.ProductResponse;
import com.dh.product.dto.SellerDtos.SellerCreateRequest;
import com.dh.product.dto.SellerDtos.SellerDetailResponse;
import com.dh.product.dto.SellerDtos.SellerResponse;
import com.dh.product.dto.SellerDtos.SellerStatusHistoryResponse;
import com.dh.product.repository.CategoryRepository;
import com.dh.product.repository.ChannelRepository;
import com.dh.product.repository.InventoryRepository;
import com.dh.product.repository.InventoryTransactionRepository;
import com.dh.product.repository.ProductRepository;
import com.dh.product.repository.ProductVariantRepository;
import com.dh.product.repository.SellerRepository;
import com.dh.product.repository.SellerStatusHistoryRepository;

/**
 * product.api#29 완료 기준의 회귀 테스트: 공급사 1곳을 등록해 DRAFT → ACTIVE 까지 전이시키고
 * 그 판매자에 상품을 귀속시킬 수 있어야 한다.
 *
 * <p>상태머신을 {@code boolean approved} 로 되돌리는 리팩터링이 조용히 통과하지 않게 하는 것이
 * 이 테스트의 목적이다 - 중간 상태(SUBMITTED/IN_REVIEW)를 건너뛰는 전이가 거부되는지까지 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class SellerLifecycleIntegrationTest {

    @Container
    @ServiceConnection
    // 이 테스트 자체는 vector 확장을 쓰지 않는다. 다만 product.api#46(PR #51)이 머지되면
    // V15 가 CREATE EXTENSION vector 를 하고, 그 시점부터 이 저장소의 모든 @SpringBootTest 가
    // Flyway 마이그레이션 단계에서 부팅에 실패한다 - 머지 순서와 무관하게 깨지지 않도록 미리 맞춘다.
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

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

    private static final Long FIRST_PARTY_SELLER_ID = 1L;

    @Autowired
    private SellerService sellerService;
    @Autowired
    private ProductService productService;
    @Autowired
    private SellerRepository sellerRepository;
    @Autowired
    private SellerStatusHistoryRepository sellerStatusHistoryRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private ProductVariantRepository variantRepository;
    @Autowired
    private InventoryRepository inventoryRepository;
    @Autowired
    private InventoryTransactionRepository inventoryTransactionRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private ChannelRepository channelRepository;

    private Long categoryId;

    @BeforeEach
    void setUp() {
        // createProduct 가 기본 variant + 재고 + 재고 트랜잭션까지 만들므로 FK 역순으로 지운다.
        inventoryTransactionRepository.deleteAll();
        inventoryRepository.deleteAll();
        variantRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        channelRepository.deleteAll();
        // 자사 판매자(id=1)는 V14 가 시드한 것이라 지우지 않는다 - 지우면 products.seller_id 기본값이
        // 가리킬 행이 사라진다. 앞선 테스트가 만든 공급사만 걷어낸다.
        sellerRepository.findAll().stream()
                .filter(s -> !FIRST_PARTY_SELLER_ID.equals(s.getId()))
                .forEach(sellerRepository::delete);

        Channel channel = new Channel("종합몰", "posselect.com");
        channelRepository.save(channel);

        Category category = new Category();
        category.setName("테스트 카테고리");
        category.setChannel(channel);
        categoryRepository.save(category);
        categoryId = category.getId();
    }

    @Test
    @DisplayName("V14가 자사 판매자를 id=1로 시드한다 — order.api의 order_items.seller_id=1과 맞춰야 한다")
    void firstPartySellerIsSeeded() {
        var firstParty = sellerRepository.findById(FIRST_PARTY_SELLER_ID);

        assertThat(firstParty).isPresent();
        assertThat(firstParty.get().getType()).isEqualTo(SellerType.FIRST_PARTY);
        assertThat(firstParty.get().getStatus()).isEqualTo(SellerStatus.ACTIVE);
    }

    @Test
    @DisplayName("공급사를 등록해 DRAFT → ACTIVE 까지 전이시키고 그 판매자에 상품을 귀속시킨다")
    void supplierGoesFromDraftToActiveAndOwnsProducts() {
        SellerResponse created = sellerService.createSeller(new SellerCreateRequest(
                "테스트공급사", "123-45-67890", null, "홍길동", "서울시 어딘가", "02-0000-0000",
                "supplier@example.com"));

        assertThat(created.status()).isEqualTo(SellerStatus.DRAFT.name());
        assertThat(created.type()).isEqualTo(SellerType.SUPPLIER.name());

        sellerService.transition(created.id(), SellerStatus.SUBMITTED, null, null, "admin@posselect.com");
        sellerService.transition(created.id(), SellerStatus.IN_REVIEW, null, null, "admin@posselect.com");
        SellerResponse active = sellerService.transition(
                created.id(), SellerStatus.ACTIVE, null, "서류 확인 완료", "admin@posselect.com");

        assertThat(active.status()).isEqualTo(SellerStatus.ACTIVE.name());

        ProductResponse product = productService.createProduct(new ProductCreateRequest(
                categoryId, "공급사 상품", "설명", new BigDecimal("5000"), 10, List.of(),
                null, null, null, null, false, null, created.id(), null));

        assertThat(product.sellerId()).isEqualTo(created.id());
        assertThat(product.sellerName()).isEqualTo("테스트공급사");
        assertThat(product.status()).isEqualTo("LIVE");
    }

    @Test
    @DisplayName("중간 심사 단계를 건너뛰는 전이는 거부된다 — 승인/거부 2값이 아니라는 것이 이 도메인의 요점이다")
    void skippingReviewGatesIsRejected() {
        SellerResponse created = sellerService.createSeller(new SellerCreateRequest(
                "성급한공급사", "123-45-67891", null, "홍길동", "서울시 어딘가", "02-0000-0000",
                "hasty@example.com"));

        assertThatThrownBy(() -> sellerService.transition(
                created.id(), SellerStatus.ACTIVE, null, null, "admin@posselect.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("허용되지 않는 전이");

        assertThat(sellerRepository.findById(created.id()).orElseThrow().getStatus())
                .isEqualTo(SellerStatus.DRAFT);
    }

    @Test
    @DisplayName("반려된 공급사는 보완 후 다시 제출할 수 있고, 전이 이력은 덮이지 않고 쌓인다")
    void rejectedSupplierCanResubmitAndHistoryAccumulates() {
        SellerResponse created = sellerService.createSeller(new SellerCreateRequest(
                "보완공급사", "123-45-67892", null, "홍길동", "서울시 어딘가", "02-0000-0000",
                "revise@example.com"));

        sellerService.transition(created.id(), SellerStatus.SUBMITTED, null, null, "admin@posselect.com");
        sellerService.transition(created.id(), SellerStatus.IN_REVIEW, null, null, "reviewer@posselect.com");
        sellerService.transition(created.id(), SellerStatus.REJECTED, "DOC_EXPIRED",
                "사업자등록증 발급일이 3개월을 넘었다", "reviewer@posselect.com");
        sellerService.transition(created.id(), SellerStatus.SUBMITTED, null, "서류 재발급", "admin@posselect.com");

        SellerDetailResponse detail = sellerService.getSeller(created.id());
        List<SellerStatusHistoryResponse> history = detail.history();

        // 최신순이므로 마지막 전이가 맨 앞이다.
        assertThat(history).hasSize(4);
        assertThat(history.get(0).toStatus()).isEqualTo(SellerStatus.SUBMITTED.name());
        assertThat(history.get(1).toStatus()).isEqualTo(SellerStatus.REJECTED.name());
        assertThat(history.get(1).reasonCode()).isEqualTo("DOC_EXPIRED");
        assertThat(history.get(1).changedBy())
                .as("승인/반려를 누가 했는지는 사후 설명 책임이라 요청 본문이 아니라 인증 주체에서 와야 한다")
                .isEqualTo("reviewer@posselect.com");
        assertThat(sellerStatusHistoryRepository.findBySellerIdOrderByIdDesc(created.id())).hasSize(4);
    }

    @Test
    @DisplayName("sellerId를 생략하면 자사 판매자(id=1)에 귀속된다 — 기존 상품 등록 폼이 깨지지 않아야 한다")
    void productWithoutSellerIdFallsBackToFirstParty() {
        ProductResponse product = productService.createProduct(new ProductCreateRequest(
                categoryId, "자사 상품", "설명", new BigDecimal("5000"), 10, List.of(),
                null, null, null, null, false, null, null, null));

        assertThat(product.sellerId()).isEqualTo(FIRST_PARTY_SELLER_ID);
        assertThat(product.status()).isEqualTo("LIVE");
    }
}
