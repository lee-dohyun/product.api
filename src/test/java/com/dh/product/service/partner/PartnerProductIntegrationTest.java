package com.dh.product.service.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

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
import com.dh.product.domain.Seller;
import com.dh.product.domain.SellerCategoryPermission;
import com.dh.product.domain.SellerStatus;
import com.dh.product.domain.SellerType;
import com.dh.product.domain.SubmissionStatus;
import com.dh.product.dto.PartnerDtos.PartnerProductRequest;
import com.dh.product.dto.SubmissionDtos.ProductAttributeValue;
import com.dh.product.repository.CategoryRepository;
import com.dh.product.repository.SellerCategoryPermissionRepository;
import com.dh.product.repository.SellerRepository;
import com.dh.product.service.ProductService;
import com.dh.product.service.submission.ProductSubmissionService;
import com.dh.product.service.submission.SubmissionValidationPublisher;

/**
 * product.api#75 - 파트너 등록 흐름을 실제 DB 로 끝까지 돌린다.
 *
 * <p>검증 대상: (1) 파트너가 만든 상품은 DRAFT 라 쇼핑몰에 안 보인다 (2) 다른 판매자는 404
 * (3) 고시 누락 → NEEDS_FIX → 보완 → 재제출 → IN_REVIEW (4) 검수 중에는 수정 409
 * (5) 승인 후에야 공개되고, 판매 중 상품은 파트너가 직접 못 고친다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class PartnerProductIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @TestConfiguration
    static class LocalCacheConfig {
        @Bean
        @Primary
        CacheManager testCacheManager() {
            return new ConcurrentMapCacheManager(
                    CacheNames.PRODUCT, CacheNames.MAIN_BEST, CacheNames.MAIN_NEW, CacheNames.MAIN_BY_CATEGORY);
        }
    }

    /** V8 식품 > 신선식품. V16 이 고시 항목 5개 + restricted 를 얹는다. */
    private static final Long FRESH_FOOD_CATEGORY_ID = 9108L;

    @Autowired
    private PartnerProductService partnerProductService;
    @Autowired
    private ProductSubmissionService submissionService;
    @Autowired
    private SubmissionValidationPublisher validationPublisher;
    @Autowired
    private ProductService productService;
    @Autowired
    private SellerRepository sellerRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private SellerCategoryPermissionRepository permissionRepository;

    private Seller partner;
    private Seller otherPartner;

    @BeforeEach
    void setUp() {
        partner = sellerRepository.save(seller("파트너A", "222-22-22222"));
        otherPartner = sellerRepository.save(seller("파트너B", "333-33-33333"));
        Category food = categoryRepository.findById(FRESH_FOOD_CATEGORY_ID).orElseThrow();
        permissionRepository.save(new SellerCategoryPermission(partner, food, "test"));
    }

    private static Seller seller(String name, String brn) {
        Seller s = new Seller();
        s.setName(name);
        s.setBusinessRegistrationNo(brn);
        s.setRepresentativeName("대표");
        s.setAddress("서울시");
        s.setPhone("010-0000-0000");
        s.setEmail(name + "@example.com");
        s.setStatus(SellerStatus.ACTIVE);
        s.setType(SellerType.SUPPLIER);
        return s;
    }

    private static PartnerProductRequest foodRequest(String name) {
        return new PartnerProductRequest(FRESH_FOOD_CATEGORY_ID, name, "설명", new BigDecimal("9900"), 10,
                List.of("https://image.posselect.com/cdn/products/food.png"), null, false, "브랜드");
    }

    private static List<ProductAttributeValue> notice() {
        return List.of(
                new ProductAttributeValue("origin", "국산"),
                new ProductAttributeValue("manufacturer", "제조사"),
                new ProductAttributeValue("expiry", "제조일로부터 7일"),
                new ProductAttributeValue("storage", "냉장 보관"),
                new ProductAttributeValue("as_contact", "1588-0000"));
    }

    private SubmissionStatus submitAndValidate(Long productId) {
        Long submissionId = partnerProductService.submit(partner.getId(), productId, "a@example.com");
        validationPublisher.publish(submissionId);
        return submissionService.get(submissionId).getStatus();
    }

    @Test
    @DisplayName("파트너가 만든 상품은 요청과 무관하게 DRAFT 이고 쇼핑몰 목록에 안 나온다")
    void createdAsDraftAndHidden() {
        Long id = partnerProductService.create(partner.getId(), foodRequest("숨김 사과")).id();

        assertThat(partnerProductService.get(partner.getId(), id).status()).isEqualTo("DRAFT");
        assertThat(productService.listProducts(FRESH_FOOD_CATEGORY_ID, null))
                .noneMatch(p -> p.id().equals(id));
        assertThat(partnerProductService.list(partner.getId()))
                .anyMatch(p -> p.id().equals(id) && p.status().equals("DRAFT") && p.submissionStatus() == null);
    }

    @Test
    @DisplayName("다른 판매자의 상품은 조회·수정·제출 모두 404 다")
    void otherSellerGetsNotFound() {
        Long id = partnerProductService.create(partner.getId(), foodRequest("남의 상품")).id();

        assertThatThrownBy(() -> partnerProductService.get(otherPartner.getId(), id))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> partnerProductService.update(otherPartner.getId(), id, foodRequest("탈취")))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> partnerProductService.submit(otherPartner.getId(), id, "b@example.com"))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(partnerProductService.list(otherPartner.getId())).noneMatch(p -> p.id().equals(id));
    }

    @Test
    @DisplayName("고시 누락 → NEEDS_FIX → 보완 후 같은 버튼으로 재제출 → IN_REVIEW → 검수 중 수정 409 → 승인 후 공개")
    void fullSubmissionFlow() {
        Long id = partnerProductService.create(partner.getId(), foodRequest("유기농 사과")).id();

        assertThat(submitAndValidate(id)).isEqualTo(SubmissionStatus.NEEDS_FIX);
        var latest = partnerProductService.latestSubmission(partner.getId(), id, "a@example.com");
        assertThat(latest.issues()).anyMatch(i -> i.code().equals("ATTRIBUTE_REQUIRED"));

        partnerProductService.replaceAttributes(partner.getId(), id, notice());
        assertThat(submitAndValidate(id)).isEqualTo(SubmissionStatus.IN_REVIEW);
        // 재제출은 새 제출이 아니라 같은 제출을 다시 돌린다 - 심사 이력이 한 줄로 이어져야 한다.
        assertThat(partnerProductService.latestSubmission(partner.getId(), id, "a@example.com").id())
                .isEqualTo(latest.id());

        assertThatThrownBy(() -> partnerProductService.update(partner.getId(), id, foodRequest("몰래 수정")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> partnerProductService.submit(partner.getId(), id, "a@example.com"))
                .isInstanceOf(IllegalStateException.class);

        submissionService.approve(latest.id(), "reviewer@posselect.com", null);

        assertThat(productService.listProducts(FRESH_FOOD_CATEGORY_ID, null)).anyMatch(p -> p.id().equals(id));
        assertThatThrownBy(() -> partnerProductService.update(partner.getId(), id, foodRequest("판매 중 수정")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("파트너가 가격을 고치면 쇼핑몰 대표가(오퍼 가격)도 바뀐다")
    void priceUpdateReachesOffer() {
        Long id = partnerProductService.create(partner.getId(), foodRequest("가격 테스트")).id();
        PartnerProductRequest cheaper = new PartnerProductRequest(FRESH_FOOD_CATEGORY_ID, "가격 테스트", "설명",
                new BigDecimal("5000"), 10, List.of("https://image.posselect.com/cdn/products/food.png"),
                null, false, "브랜드");

        partnerProductService.update(partner.getId(), id, cheaper);

        assertThat(partnerProductService.list(partner.getId()))
                .filteredOn(p -> p.id().equals(id))
                .singleElement()
                .satisfies(p -> assertThat(p.price()).isEqualByComparingTo("5000"));
    }

    @Test
    @DisplayName("같은 상품에 진행 중 제출을 두 번 만들 수 없다 - 잠금을 안 거치는 관리자 경로도 DB 가 막는다(V19)")
    void secondOpenSubmissionIsRejectedByDatabase() {
        Long id = partnerProductService.create(partner.getId(), foodRequest("중복 제출")).id();
        submissionService.submit(id, "a@example.com");

        assertThatThrownBy(() -> submissionService.submit(id, "a@example.com"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
