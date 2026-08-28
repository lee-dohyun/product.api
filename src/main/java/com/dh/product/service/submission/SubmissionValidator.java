package com.dh.product.service.submission;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.dh.product.domain.CategoryRequirement;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductAttribute;
import com.dh.product.domain.ProductVariant;
import com.dh.product.domain.Seller;
import com.dh.product.domain.SellerStatus;
import com.dh.product.repository.CategoryRequirementRepository;
import com.dh.product.repository.ProductAttributeRepository;
import com.dh.product.repository.ProductVariantRepository;
import com.dh.product.repository.SellerCategoryPermissionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 상품 제출 검증 규칙 엔진(product.api#30).
 *
 * <p>규칙을 사람 심사 앞에 두는 것이 이 클래스의 존재 이유다 - 규칙이 걸러낸 것만
 * {@code IN_REVIEW} 큐에 올라간다.
 *
 * <p>이 클래스는 DB 를 읽기만 하고 아무것도 쓰지 않는다. 결과를 저장하는 책임은
 * {@code ProductSubmissionService} 에 있다.
 */
@Component
public class SubmissionValidator {

    private static final Logger logger = LoggerFactory.getLogger(SubmissionValidator.class);

    /**
     * 금지어. 「표시·광고의 공정화에 관한 법률」이 막는 절대적 표현의 최소 집합이다.
     * 상수로 두는 이유: 고시 항목(카테고리마다 다르고 개정된다)과 달리 이건 전 카테고리 공통이고
     * 거의 바뀌지 않는다. 카테고리별 금지어가 필요해지면 category_requirements 로 옮긴다.
     */
    private static final List<String> BANNED_WORDS = List.of("최고", "1위", "100% 보장", "국내 유일", "완치");

    private final CategoryRequirementRepository categoryRequirementRepository;
    private final ProductAttributeRepository productAttributeRepository;
    private final ProductVariantRepository productVariantRepository;
    private final SellerCategoryPermissionRepository sellerCategoryPermissionRepository;
    private final ObjectMapper objectMapper;

    public SubmissionValidator(
            CategoryRequirementRepository categoryRequirementRepository,
            ProductAttributeRepository productAttributeRepository,
            ProductVariantRepository productVariantRepository,
            SellerCategoryPermissionRepository sellerCategoryPermissionRepository) {
        this.categoryRequirementRepository = categoryRequirementRepository;
        this.productAttributeRepository = productAttributeRepository;
        this.productVariantRepository = productVariantRepository;
        this.sellerCategoryPermissionRepository = sellerCategoryPermissionRepository;
        this.objectMapper = new ObjectMapper();
    }

    public List<ValidationFinding> validate(Product product, Seller seller) {
        List<ValidationFinding> findings = new ArrayList<>();
        validateSellerGate(seller, findings);
        validateBasics(product, findings);
        validateVariants(product, findings);
        validateBannedWords(product, findings);
        validateCategoryRequirement(product, seller, findings);
        return findings;
    }

    /** 판매자 게이트 - ACTIVE 가 아니면 나머지를 볼 것도 없다. */
    private void validateSellerGate(Seller seller, List<ValidationFinding> findings) {
        if (seller.getStatus() != SellerStatus.ACTIVE) {
            findings.add(ValidationFinding.blocking("SELLER_NOT_ACTIVE", null,
                    "판매자 상태가 ACTIVE 가 아닙니다(현재: " + seller.getStatus() + "). 입점 심사를 먼저 통과해야 합니다."));
        }
    }

    private void validateBasics(Product product, List<ValidationFinding> findings) {
        if (product.getName() == null || product.getName().isBlank()) {
            findings.add(ValidationFinding.blocking("NAME_REQUIRED", "name", "상품명은 필수입니다."));
        }
        if (product.getImages().isEmpty()) {
            findings.add(ValidationFinding.blocking("IMAGE_REQUIRED", "imageUrls",
                    "상품 이미지가 최소 1장 필요합니다."));
        }
        if (product.getDescription() == null || product.getDescription().isBlank()) {
            findings.add(ValidationFinding.warning("DESCRIPTION_EMPTY", "description",
                    "상품 설명이 비어 있습니다."));
        }
        if (product.getListPrice() != null && product.getBrand() == null) {
            findings.add(ValidationFinding.warning("BRAND_EMPTY", "brand",
                    "정가를 입력했는데 브랜드가 비어 있습니다."));
        }
    }

    private void validateVariants(Product product, List<ValidationFinding> findings) {
        List<ProductVariant> active = productVariantRepository.findByProductId(product.getId()).stream()
                .filter(ProductVariant::isActive)
                .toList();
        if (active.isEmpty()) {
            findings.add(ValidationFinding.blocking("NO_ACTIVE_SKU", null,
                    "판매 가능한 SKU 가 하나도 없습니다."));
            return;
        }
        boolean nonPositivePrice = active.stream()
                .anyMatch(v -> v.getPrice() == null || v.getPrice().compareTo(BigDecimal.ZERO) <= 0);
        if (nonPositivePrice) {
            findings.add(ValidationFinding.blocking("PRICE_OUT_OF_RANGE", "price",
                    "판매가가 0원 이하인 SKU 가 있습니다."));
        }
        // 정가가 판매가보다 낮으면 할인율이 음수가 되어 카드에 이상하게 표시된다.
        if (product.getListPrice() != null) {
            BigDecimal lowest = active.stream()
                    .map(ProductVariant::getPrice)
                    .filter(java.util.Objects::nonNull)
                    .min(BigDecimal::compareTo)
                    .orElse(null);
            if (lowest != null && product.getListPrice().compareTo(lowest) < 0) {
                findings.add(ValidationFinding.blocking("LIST_PRICE_BELOW_SALE_PRICE", "listPrice",
                        "정가(" + product.getListPrice() + ")가 판매가(" + lowest + ")보다 낮습니다."));
            }
        }
    }

    private void validateBannedWords(Product product, List<ValidationFinding> findings) {
        String name = product.getName() != null ? product.getName() : "";
        String description = product.getDescription() != null ? product.getDescription() : "";
        for (String word : BANNED_WORDS) {
            if (name.contains(word)) {
                findings.add(ValidationFinding.blocking("BANNED_WORD", "name",
                        "상품명에 사용할 수 없는 표현이 있습니다: " + word));
            } else if (description.contains(word)) {
                findings.add(ValidationFinding.warning("BANNED_WORD", "description",
                        "상품 설명에 사용할 수 없는 표현이 있습니다: " + word));
            }
        }
    }

    /**
     * 카테고리가 나머지를 결정한다 - 필수 고시 항목과 판매권한.
     * 카테고리에 요건이 등록돼 있지 않으면 규제 대상이 아니라는 뜻이라 통과시킨다.
     */
    private void validateCategoryRequirement(Product product, Seller seller, List<ValidationFinding> findings) {
        Long categoryId = product.getCategory().getId();
        Optional<CategoryRequirement> maybeRequirement = categoryRequirementRepository.findByCategoryId(categoryId);
        if (maybeRequirement.isEmpty()) {
            return;
        }
        CategoryRequirement requirement = maybeRequirement.get();

        if (requirement.isRestricted()
                && !sellerCategoryPermissionRepository.existsBySellerIdAndCategoryId(seller.getId(), categoryId)) {
            findings.add(ValidationFinding.blocking("CATEGORY_PERMISSION_REQUIRED", "categoryId",
                    "이 카테고리는 사전 판매권한이 필요합니다. 인허가 서류를 제출해 권한을 받아야 합니다."));
        }

        Map<String, String> values = productAttributeRepository.findByProductId(product.getId()).stream()
                .collect(Collectors.toMap(ProductAttribute::getAttributeCode,
                        a -> a.getAttributeValue() != null ? a.getAttributeValue() : "",
                        (a, b) -> a));

        for (RequiredAttribute attribute : parseRequiredAttributes(requirement)) {
            if (!attribute.required()) {
                continue;
            }
            String value = values.get(attribute.code());
            if (value == null || value.isBlank()) {
                findings.add(ValidationFinding.blocking("ATTRIBUTE_REQUIRED", attribute.code(),
                        "상품정보제공고시 필수 항목이 비어 있습니다: " + attribute.label()));
            }
        }
    }

    /**
     * 정의가 깨져 있으면(수기 편집 실수 등) 제출을 통과시키지 않고 blocking 으로 드러낸다 -
     * 조용히 빈 목록으로 넘기면 고시 항목 검사가 통째로 사라진 것을 아무도 모른다.
     */
    private List<RequiredAttribute> parseRequiredAttributes(CategoryRequirement requirement) {
        try {
            return objectMapper.readValue(requirement.getRequiredAttributes(),
                    new TypeReference<List<RequiredAttribute>>() { });
        } catch (Exception e) {
            logger.error("category_requirements.required_attributes 파싱 실패 categoryId={}",
                    requirement.getCategory().getId(), e);
            throw new IllegalStateException(
                    "카테고리 고시 항목 정의가 손상되었습니다(categoryId="
                            + requirement.getCategory().getId() + "). 관리자 확인이 필요합니다.", e);
        }
    }

    /** admin.front 가 카테고리 선택 직후 동적 폼을 그릴 때 쓴다. */
    public List<RequiredAttribute> requiredAttributesFor(Long categoryId) {
        return categoryRequirementRepository.findByCategoryId(categoryId)
                .map(this::parseRequiredAttributes)
                .orElseGet(List::of);
    }
}
