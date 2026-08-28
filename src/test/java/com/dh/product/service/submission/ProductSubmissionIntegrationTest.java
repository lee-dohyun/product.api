package com.dh.product.service.submission;

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
import com.dh.product.domain.ProductStatus;
import com.dh.product.domain.Seller;
import com.dh.product.domain.SellerCategoryPermission;
import com.dh.product.domain.SellerStatus;
import com.dh.product.domain.SubmissionIssue;
import com.dh.product.domain.SubmissionStatus;
import com.dh.product.dto.ProductDtos.ProductCreateRequest;
import com.dh.product.dto.SubmissionDtos.ProductAttributeValue;
import com.dh.product.repository.CategoryRepository;
import com.dh.product.repository.ProductRepository;
import com.dh.product.repository.SellerCategoryPermissionRepository;
import com.dh.product.repository.SellerRepository;
import com.dh.product.service.ProductService;

/**
 * product.api#30 완료 기준의 회귀 테스트:
 * <b>식품 카테고리에 고시 항목을 비운 상품을 제출하면 NEEDS_FIX 로 떨어지고
 * submission_issues 에 누락 항목이 필드 단위로 남을 것.</b>
 *
 * <p>V8 이 시드한 실제 식품 중분류(9108 신선식품)와 V16 이 그 위에 얹은 고시 항목 정의를
 * 그대로 쓴다 - 테스트 전용 카테고리를 새로 만들면 "마이그레이션이 실제로 요건을 심었는가"가
 * 검증에서 빠진다.
 *
 * <p>규칙 검증(VALIDATING)을 사람 심사(IN_REVIEW) 앞에 두는 순서가 뒤집히면 이 테스트가 깨진다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ProductSubmissionIntegrationTest {

    /** V15 가 CREATE EXTENSION vector 를 하므로 순정 postgres 이미지로는 부팅이 실패한다. */
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
                    CacheNames.PRODUCT,
                    CacheNames.MAIN_BEST,
                    CacheNames.MAIN_NEW,
                    CacheNames.MAIN_BY_CATEGORY);
        }
    }

    /** V8 이 심은 식품 > 신선식품. V16 이 이 카테고리에 고시 항목 5개 + restricted 를 얹는다. */
    private static final Long FRESH_FOOD_CATEGORY_ID = 9108L;

    @Autowired
    private ProductSubmissionService submissionService;
    @Autowired
    private SubmissionValidationPublisher validationPublisher;
    @Autowired
    private ProductAttributeService productAttributeService;
    @Autowired
    private ProductService productService;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private SellerRepository sellerRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private SellerCategoryPermissionRepository permissionRepository;

    private Seller supplier;

    @BeforeEach
    void setUp() {
        // 카테고리는 지우지 않는다 - V8/V16 이 심은 식품 카테고리와 그 요건이 이 테스트의 검증 대상이다.
        supplier = new Seller();
        supplier.setName("테스트 공급사");
        supplier.setBusinessRegistrationNo("111-11-11111");
        supplier.setRepresentativeName("홍길동");
        supplier.setAddress("서울시");
        supplier.setPhone("010-0000-0000");
        supplier.setEmail("supplier@example.com");
        supplier.setStatus(SellerStatus.ACTIVE);
        supplier.setType(com.dh.product.domain.SellerType.SUPPLIER);
        sellerRepository.save(supplier);
    }

    private Long createFoodProduct(String name) {
        return productService.createProduct(new ProductCreateRequest(
                FRESH_FOOD_CATEGORY_ID, name, "설명", new BigDecimal("9900"), 10,
                List.of("https://image.posselect.com/cdn/products/food.png"),
                null, null, null, null, false, "테스트브랜드",
                supplier.getId(), ProductStatus.DRAFT.name())).id();
    }

    private void grantFoodPermission() {
        Category category = categoryRepository.findById(FRESH_FOOD_CATEGORY_ID).orElseThrow();
        permissionRepository.save(new SellerCategoryPermission(supplier, category, "test"));
    }

    private void fillNoticeAttributes(Long productId) {
        productAttributeService.replaceAttributes(productId, List.of(
                new ProductAttributeValue("origin", "국산"),
                new ProductAttributeValue("manufacturer", "테스트제조사"),
                new ProductAttributeValue("expiry", "제조일로부터 7일"),
                new ProductAttributeValue("storage", "냉장 보관"),
                new ProductAttributeValue("as_contact", "1588-0000")));
    }

    @Test
    @DisplayName("V16 이 식품 카테고리에 고시 항목 요건을 심는다")
    void foodCategoryHasNoticeRequirement() {
        var requirement = productAttributeService.getRequirement(FRESH_FOOD_CATEGORY_ID);

        assertThat(requirement.restricted()).isTrue();
        assertThat(requirement.requiredAttributes())
                .extracting(a -> a.code())
                .contains("origin", "manufacturer", "expiry", "storage", "as_contact");
        assertThat(requirement.requiredDocuments()).contains("FOOD_BUSINESS_LICENSE");
    }

    @Test
    @DisplayName("고시 항목을 비운 식품 상품은 NEEDS_FIX 로 떨어지고 누락 항목이 필드 단위로 남는다")
    void emptyNoticeAttributesFailValidation() {
        grantFoodPermission();
        Long productId = createFoodProduct("무항생제 계란 30구");

        Long submissionId = submissionService.submit(productId, "pm@posselect.com");
        validationPublisher.publish(submissionId);

        assertThat(submissionService.get(submissionId).getStatus()).isEqualTo(SubmissionStatus.NEEDS_FIX);

        List<SubmissionIssue> issues = submissionService.issuesOf(submissionId);
        assertThat(issues)
                .filteredOn(i -> "ATTRIBUTE_REQUIRED".equals(i.getCode()))
                .extracting(SubmissionIssue::getField)
                .containsExactlyInAnyOrder("origin", "manufacturer", "expiry", "storage", "as_contact");
    }

    @Test
    @DisplayName("판매권한 없는 restricted 카테고리는 제출이 막힌다")
    void restrictedCategoryWithoutPermissionIsBlocked() {
        Long productId = createFoodProduct("유기농 상추");
        fillNoticeAttributes(productId);

        Long submissionId = submissionService.submit(productId, "pm@posselect.com");
        validationPublisher.publish(submissionId);

        assertThat(submissionService.get(submissionId).getStatus()).isEqualTo(SubmissionStatus.NEEDS_FIX);
        assertThat(submissionService.issuesOf(submissionId))
                .extracting(SubmissionIssue::getCode)
                .contains("CATEGORY_PERMISSION_REQUIRED");
    }

    @Test
    @DisplayName("ACTIVE 가 아닌 판매자는 제출이 막힌다")
    void inactiveSellerIsBlocked() {
        grantFoodPermission();
        Long productId = createFoodProduct("냉동 삼겹살");
        fillNoticeAttributes(productId);
        supplier.setStatus(SellerStatus.SUSPENDED);
        sellerRepository.save(supplier);

        Long submissionId = submissionService.submit(productId, "pm@posselect.com");
        validationPublisher.publish(submissionId);

        assertThat(submissionService.get(submissionId).getStatus()).isEqualTo(SubmissionStatus.NEEDS_FIX);
        assertThat(submissionService.issuesOf(submissionId))
                .extracting(SubmissionIssue::getCode)
                .contains("SELLER_NOT_ACTIVE");
    }

    @Test
    @DisplayName("고시 항목을 채우면 IN_REVIEW 로 올라가고, 승인하면 상품이 LIVE 가 된다")
    void completeSubmissionReachesReviewAndGoesLive() {
        grantFoodPermission();
        Long productId = createFoodProduct("친환경 방울토마토");
        fillNoticeAttributes(productId);

        Long submissionId = submissionService.submit(productId, "pm@posselect.com");
        validationPublisher.publish(submissionId);

        assertThat(submissionService.get(submissionId).getStatus())
                .as("규칙이 통과시킨 것만 사람 심사 큐에 올라간다")
                .isEqualTo(SubmissionStatus.IN_REVIEW);

        submissionService.approve(submissionId, "reviewer@posselect.com", "확인함");

        assertThat(submissionService.get(submissionId).getStatus()).isEqualTo(SubmissionStatus.LIVE);
        assertThat(productRepository.findById(productId).orElseThrow().getStatus())
                .as("승인 시점에만 상품이 실제로 노출된다")
                .isEqualTo(ProductStatus.LIVE);
    }

    @Test
    @DisplayName("NEEDS_FIX 를 고쳐 재제출하면 재검증을 거쳐 IN_REVIEW 로 간다 — 이전 이슈는 남지 않는다")
    void resubmitAfterFixClearsPreviousIssues() {
        grantFoodPermission();
        Long productId = createFoodProduct("햇감자 5kg");

        Long submissionId = submissionService.submit(productId, "pm@posselect.com");
        validationPublisher.publish(submissionId);
        assertThat(submissionService.get(submissionId).getStatus()).isEqualTo(SubmissionStatus.NEEDS_FIX);

        fillNoticeAttributes(productId);
        submissionService.resubmit(submissionId, "pm@posselect.com");
        validationPublisher.publish(submissionId);

        assertThat(submissionService.get(submissionId).getStatus()).isEqualTo(SubmissionStatus.IN_REVIEW);
        assertThat(submissionService.issuesOf(submissionId))
                .as("재검증 시 이전 이슈는 지우고 새로 쓴다")
                .noneMatch(i -> "ATTRIBUTE_REQUIRED".equals(i.getCode()));
    }

    @Test
    @DisplayName("사람 심사를 건너뛰고 바로 LIVE 로 보내는 전이는 거부된다")
    void cannotSkipReview() {
        grantFoodPermission();
        Long productId = createFoodProduct("제철 딸기");
        fillNoticeAttributes(productId);
        Long submissionId = submissionService.submit(productId, "pm@posselect.com");

        // 아직 SUBMITTED — VALIDATING/IN_REVIEW 를 거치지 않았다
        assertThatThrownBy(() -> submissionService.approve(submissionId, "reviewer@posselect.com", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("허용되지 않는 전이");
    }
}
