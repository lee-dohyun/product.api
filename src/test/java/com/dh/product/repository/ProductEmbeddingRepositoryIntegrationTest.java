package com.dh.product.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.dh.product.domain.Category;
import com.dh.product.domain.Channel;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductStatus;
import com.dh.product.repository.ProductEmbeddingRepository.NearestMatch;

/**
 * pgvector 확장이 실제로 동작하는지 실DB로 검증한다(product.api#46) — API 키 없이도 검증 가능한
 * 부분(스키마/SQL 정합성)만 다룬다. 임베딩 API 호출 자체는 별도 유닛 테스트에서 모킹으로 검증한다.
 *
 * <p>stock postgres 이미지가 아니라 pgvector 확장이 컴파일된 이미지를 써야 V15 마이그레이션의
 * {@code CREATE EXTENSION vector}가 통과한다 — Testcontainers 기본 계정(test)은 컨테이너 안에서
 * superuser라 라이브 catalogdb(non-superuser catalog_user)와 달리 이 계정으로도 확장 설치가 된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ProductEmbeddingRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private ProductEmbeddingRepository embeddingRepository;
    @Autowired
    private ChannelRepository channelRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private SellerRepository sellerRepository;

    private Long productAId;
    private Long productBId;
    private Long productCId;

    @BeforeEach
    void setUp() {
        // V4 마이그레이션이 channels(id=1, domain='posselect.com')를 시드하는데, IDENTITY
        // 시퀀스는 그 명시적 insert를 반영하지 않고 UNIQUE(domain)도 그대로 남는다 - 매 테스트마다
        // 지우고 새로 만들어야 pkey/domain 충돌 없이 격리된다(기존 InventoryDeductionIntegrationTest와
        // 동일 패턴).
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        channelRepository.deleteAll();

        Channel channel = new Channel("종합몰", "posselect.com");
        channelRepository.save(channel);

        Category category = new Category();
        category.setName("테스트 카테고리");
        category.setChannel(channel);
        categoryRepository.save(category);

        productAId = saveProduct(category, "상품A");
        productBId = saveProduct(category, "상품B");
        productCId = saveProduct(category, "상품C");
    }

    private Long saveProduct(Category category, String name) {
        Product product = new Product();
        product.setCategory(category);
        product.setName(name);
        // products.seller_id/status 는 V14(product.api#29)부터 NOT NULL 이다. 자사 판매자(id=1)는
        // 같은 마이그레이션이 시드하므로 여기서 만들지 않고 조회해서 붙인다.
        product.setSeller(sellerRepository.findById(1L).orElseThrow());
        product.setStatus(ProductStatus.LIVE);
        productRepository.save(product);
        return product.getId();
    }

    @Test
    @DisplayName("upsert 후 findSourceHash로 저장된 해시를 다시 읽을 수 있다")
    void upsert_후_해시를_조회할_수_있다() {
        embeddingRepository.upsert(productAId, vectorOf(1f, 0f, 0f), "text-embedding-3-small", "hash-a");

        Optional<String> hash = embeddingRepository.findSourceHash(productAId);

        assertThat(hash).contains("hash-a");
    }

    @Test
    @DisplayName("같은 product_id로 upsert하면 새 값으로 갱신된다(ON CONFLICT)")
    void 같은_상품_upsert는_갱신한다() {
        embeddingRepository.upsert(productAId, vectorOf(1f, 0f, 0f), "text-embedding-3-small", "hash-1");
        embeddingRepository.upsert(productAId, vectorOf(0f, 1f, 0f), "text-embedding-3-small", "hash-2");

        assertThat(embeddingRepository.findSourceHash(productAId)).contains("hash-2");
    }

    @Test
    @DisplayName("findNearest는 코사인 거리가 가까운 순서로 반환한다")
    void findNearest는_거리순으로_정렬한다() {
        // A=(1,0,0), B=(0.9,0.1,0) B가 A와 거의 같은 방향, C=(0,0,1) 완전히 다른 방향
        embeddingRepository.upsert(productAId, vectorOf(1f, 0f, 0f), "text-embedding-3-small", "a");
        embeddingRepository.upsert(productBId, vectorOf(0.9f, 0.1f, 0f), "text-embedding-3-small", "b");
        embeddingRepository.upsert(productCId, vectorOf(0f, 0f, 1f), "text-embedding-3-small", "c");

        List<NearestMatch> matches = embeddingRepository.findNearest(vectorOf(1f, 0f, 0f), 3);

        assertThat(matches).extracting(NearestMatch::productId)
                .containsExactly(productAId, productBId, productCId);
        assertThat(matches.get(0).distance()).isLessThan(matches.get(2).distance());
    }

    @Test
    @DisplayName("findNearest의 topK는 결과 개수를 제한한다")
    void findNearest는_topK_만큼만_반환한다() {
        embeddingRepository.upsert(productAId, vectorOf(1f, 0f, 0f), "text-embedding-3-small", "a");
        embeddingRepository.upsert(productBId, vectorOf(0.9f, 0.1f, 0f), "text-embedding-3-small", "b");
        embeddingRepository.upsert(productCId, vectorOf(0f, 0f, 1f), "text-embedding-3-small", "c");

        List<NearestMatch> matches = embeddingRepository.findNearest(vectorOf(1f, 0f, 0f), 1);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).productId()).isEqualTo(productAId);
    }

    private static float[] vectorOf(float... values) {
        // 1536차원 컬럼이지만 테스트는 앞부분만 채우고 나머지는 0으로 둬도 코사인 거리 비교엔
        // 문제없다 - 실제 모델 차원과 정확히 맞출 필요는 여기서는 없다.
        float[] padded = new float[1536];
        System.arraycopy(values, 0, padded, 0, values.length);
        return padded;
    }
}
