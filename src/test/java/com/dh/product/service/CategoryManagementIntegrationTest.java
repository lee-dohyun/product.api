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
import org.testcontainers.utility.DockerImageName;

import com.dh.product.config.CacheNames;
import com.dh.product.domain.Category;
import com.dh.product.domain.Channel;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductStatus;
import com.dh.product.domain.ProductVariant;
import com.dh.product.dto.ProductDtos.CategoryCreateRequest;
import com.dh.product.dto.ProductDtos.CategoryResponse;
import com.dh.product.dto.ProductDtos.CategoryUpdateRequest;
import com.dh.product.repository.CategoryRepository;
import com.dh.product.repository.ChannelRepository;
import com.dh.product.repository.InventoryRepository;
import com.dh.product.repository.InventoryTransactionRepository;
import com.dh.product.repository.ProductRepository;
import com.dh.product.repository.ProductVariantRepository;
import com.dh.product.repository.SellerRepository;

/**
 * 카테고리 관리(수정/삭제/정렬) 통합 테스트 — product.api#61.
 *
 * <p><b>왜 목 기반 단위 테스트가 아닌가.</b> 이 기능이 지키려는 것 중 셋은 목으로는 성립하지
 * 않는다.
 * <ul>
 *   <li><b>정렬</b> — "ORDER BY 가 없으면 UPDATE 후 순서가 바뀐다"는 Postgres 힙 동작이다.
 *       목 리포지토리는 넣은 순서를 그대로 돌려주므로 결함이 재현되지 않는다. 이 파일의
 *       {@code 이름을_수정해도_노출_순서가_유지된다} 가 이 이슈의 핵심 회귀 방지 테스트다.</li>
 *   <li><b>삭제 차단</b> — {@code products.category_id} NOT NULL FK 가 진짜로 있어야 한다.</li>
 *   <li><b>이름 중복</b> — V4 의 (channel, parent, name) 유니크 제약이 있어야 하고,
 *       {@code saveAndFlush} 가 플러시 시점을 앞당겨야 잡힌다.</li>
 * </ul>
 *
 * <p>검증은 서비스 반환값이 아니라 {@link JdbcTemplate} 으로 <b>커밋된 행</b>을 읽어서 한다
 * (캐논 §3, posselect #211 의 교훈).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class CategoryManagementIntegrationTest {

    @Container
    @ServiceConnection
    // V15 부터 Flyway 히스토리에 vector 확장이 포함돼 stock postgres 이미지로는 마이그레이션이
    // 실패한다(InventoryDeductionIntegrationTest 와 같은 이유).
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 를 띄우지 않으려고 로컬 캐시로 바꾼다. 이름을 빠뜨리면 evict 시점에 터진다. */
    @TestConfiguration
    static class LocalCacheConfig {
        @Bean
        @Primary
        CacheManager testCacheManager() {
            return new ConcurrentMapCacheManager(
                    CacheNames.PRODUCT,
                    CacheNames.MAIN_BEST,
                    CacheNames.MAIN_NEW,
                    CacheNames.MAIN_BY_CATEGORY,
                    CacheNames.MAIN_BANNERS);
        }
    }

    @Autowired
    private CategoryService categoryService;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private ChannelRepository channelRepository;
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

    private Long channelId;

    @BeforeEach
    void setUp() {
        inventoryTransactionRepository.deleteAll();
        inventoryRepository.deleteAll();
        variantRepository.deleteAll();
        productRepository.deleteAll();
        // categories 는 자기참조 FK 라 deleteAll() 을 쓰면 안 된다. SimpleJpaRepository.deleteAll()
        // 은 엔티티마다 em.merge() 를 거치는데, 부모가 먼저 제거되면 자식의 lazy parent 프록시가
        // null 로 병합되면서 (channel_id, null, name) 유니크 제약에 걸린다 - 이 테스트를 처음
        // 돌렸을 때 실제로 3건이 이 이유로 깨졌다(같은 이름의 중분류가 둘 있는 케이스).
        // 삭제 순서를 직접 지정해 자식부터 지운다.
        jdbcTemplate.update("DELETE FROM categories WHERE parent_id IS NOT NULL");
        jdbcTemplate.update("DELETE FROM categories");
        channelRepository.deleteAll();

        Channel channel = new Channel("종합몰", "posselect.com");
        channelRepository.save(channel);
        channelId = channel.getId();
    }

    private Long create(String name, Long parentId) {
        return categoryService.create(channelId, new CategoryCreateRequest(name, parentId)).id();
    }

    /** 서비스 반환값이 아니라 커밋된 행을 노출 순서대로 읽는다. */
    private List<String> committedTopLevelNames() {
        return jdbcTemplate.queryForList(
                "SELECT name FROM categories WHERE parent_id IS NULL ORDER BY sort_order, id",
                String.class);
    }

    private void attachProduct(Long categoryId) {
        Product product = new Product();
        product.setCategory(categoryRepository.findById(categoryId).orElseThrow());
        product.setName("테스트 상품");
        // seller_id/status 는 V14 부터 NOT NULL. 자사 판매자(id=1)는 마이그레이션이 시드한다.
        product.setSeller(sellerRepository.findById(1L).orElseThrow());
        product.setStatus(ProductStatus.LIVE);
        productRepository.save(product);
        variantRepository.save(new ProductVariant(product, "SKU-CAT-1", new BigDecimal("10000.00")));
    }

    // ---------------------------------------------------------------
    // 정렬 — 이 이슈의 핵심
    // ---------------------------------------------------------------

    @Test
    @DisplayName("이름을 수정해도 노출 순서가 유지된다 — ORDER BY 가 없으면 여기서 깨진다")
    void 이름을_수정해도_노출_순서가_유지된다() {
        Long fashion = create("패션의류", null);
        create("뷰티", null);
        create("식품", null);

        // 첫 번째 카테고리를 수정한다. ORDER BY 가 없으면 이 행이 힙 끝으로 밀려
        // 목록에서 마지막으로 내려간다(라이브 DB 에서 실측한 동작).
        categoryService.update(fashion, new CategoryUpdateRequest("패션의류(수정)", null, null));

        assertThat(committedTopLevelNames())
                .containsExactly("패션의류(수정)", "뷰티", "식품");
        assertThat(categoryService.list(channelId))
                .extracting(CategoryResponse::name)
                .containsExactly("패션의류(수정)", "뷰티", "식품");
    }

    @Test
    @DisplayName("새 카테고리는 형제 맨 뒤에 붙는다")
    void 새_카테고리는_형제_맨뒤에_붙는다() {
        create("패션의류", null);
        create("뷰티", null);

        assertThat(categoryService.list(channelId))
                .extracting(CategoryResponse::sortOrder)
                .containsExactly((short) 1, (short) 2);
    }

    @Test
    @DisplayName("sortOrder 를 바꾸면 목록 순서가 그대로 따라온다")
    void 순서를_바꾸면_목록이_따라온다() {
        Long fashion = create("패션의류", null);
        Long beauty = create("뷰티", null);

        // 위/아래 이동 = 두 형제의 sortOrder 를 맞바꾸는 것
        categoryService.update(fashion, new CategoryUpdateRequest("패션의류", null, (short) 2));
        categoryService.update(beauty, new CategoryUpdateRequest("뷰티", null, (short) 1));

        assertThat(committedTopLevelNames()).containsExactly("뷰티", "패션의류");
    }

    // ---------------------------------------------------------------
    // 삭제 차단
    // ---------------------------------------------------------------

    @Test
    @DisplayName("상품이 달린 카테고리는 삭제되지 않고 409 사유를 알려준다")
    void 상품이_달린_카테고리는_삭제되지_않는다() {
        Long fashion = create("패션의류", null);
        attachProduct(fashion);

        assertThatThrownBy(() -> categoryService.delete(fashion))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("상품 1개");

        assertThat(categoryRepository.findById(fashion)).isPresent();
    }

    @Test
    @DisplayName("하위 카테고리가 있는 카테고리는 삭제되지 않는다")
    void 하위가_있는_카테고리는_삭제되지_않는다() {
        Long fashion = create("패션의류", null);
        create("상의", fashion);

        assertThatThrownBy(() -> categoryService.delete(fashion))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("하위 카테고리 1개");
    }

    @Test
    @DisplayName("비어 있는 카테고리는 삭제되고 행이 실제로 사라진다")
    void 비어있는_카테고리는_삭제된다() {
        Long temp = create("임시", null);

        categoryService.delete(temp);

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM categories WHERE id = ?", Integer.class, temp);
        assertThat(remaining).isZero();
    }

    // ---------------------------------------------------------------
    // 계층 규칙
    // ---------------------------------------------------------------

    @Test
    @DisplayName("자기 자신을 상위로 지정할 수 없다")
    void 자기자신을_상위로_둘_수_없다() {
        Long fashion = create("패션의류", null);

        assertThatThrownBy(() ->
                categoryService.update(fashion, new CategoryUpdateRequest("패션의류", fashion, null)))
                .isInstanceOf(CategoryHierarchyException.class);
    }

    @Test
    @DisplayName("자기 하위 카테고리를 상위로 지정할 수 없다 — 순환 참조")
    void 자손을_상위로_둘_수_없다() {
        Long fashion = create("패션의류", null);
        Long top = create("상의", fashion);

        assertThatThrownBy(() ->
                categoryService.update(fashion, new CategoryUpdateRequest("패션의류", top, null)))
                .isInstanceOf(CategoryHierarchyException.class);
    }

    @Test
    @DisplayName("중분류 밑으로 또 넣으면 3뎁스라 거부된다")
    void 삼뎁스는_거부된다() {
        Long fashion = create("패션의류", null);
        Long top = create("상의", fashion);
        Long beauty = create("뷰티", null);

        assertThatThrownBy(() ->
                categoryService.update(beauty, new CategoryUpdateRequest("뷰티", top, null)))
                .isInstanceOf(CategoryHierarchyException.class);
    }

    @Test
    @DisplayName("하위가 있는 카테고리는 다른 카테고리 밑으로 옮길 수 없다 — 옮기면 3뎁스가 된다")
    void 자식이_있으면_이동할_수_없다() {
        Long fashion = create("패션의류", null);
        create("상의", fashion);
        Long beauty = create("뷰티", null);

        assertThatThrownBy(() ->
                categoryService.update(fashion, new CategoryUpdateRequest("패션의류", beauty, null)))
                .isInstanceOf(CategoryHierarchyException.class);
    }

    @Test
    @DisplayName("같은 상위 아래 같은 이름은 409 — V4 유니크 제약이 실제로 막는다")
    void 형제간_이름_중복은_거부된다() {
        create("패션의류", null);

        assertThatThrownBy(() -> create("패션의류", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("같은 이름");
    }

    @Test
    @DisplayName("상위가 다르면 같은 이름을 쓸 수 있다")
    void 상위가_다르면_같은_이름이_허용된다() {
        Long fashion = create("패션의류", null);
        Long beauty = create("뷰티", null);

        create("신상품", fashion);
        create("신상품", beauty);

        assertThat(categoryService.list(channelId))
                .extracting(CategoryResponse::name)
                .filteredOn("신상품"::equals)
                .hasSize(2);
    }
}
