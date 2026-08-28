package com.dh.product.service.submission;

import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.product.domain.CategoryRequirement;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductAttribute;
import com.dh.product.dto.SubmissionDtos.CategoryRequirementResponse;
import com.dh.product.dto.SubmissionDtos.ProductAttributeResponse;
import com.dh.product.dto.SubmissionDtos.ProductAttributeValue;
import com.dh.product.dto.SubmissionDtos.RequiredAttributeResponse;
import com.dh.product.repository.CategoryRequirementRepository;
import com.dh.product.repository.ProductAttributeRepository;
import com.dh.product.repository.ProductRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 카테고리 요건 조회와 상품 고시 항목 입력을 담당한다(product.api#30).
 * 검증 자체는 {@link SubmissionValidator} 가 한다 - 여기는 값을 넣고 빼는 곳이다.
 */
@Service
@Transactional(readOnly = true)
public class ProductAttributeService {

    private final CategoryRequirementRepository categoryRequirementRepository;
    private final ProductAttributeRepository productAttributeRepository;
    private final ProductRepository productRepository;
    private final SubmissionValidator validator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProductAttributeService(
            CategoryRequirementRepository categoryRequirementRepository,
            ProductAttributeRepository productAttributeRepository,
            ProductRepository productRepository,
            SubmissionValidator validator) {
        this.categoryRequirementRepository = categoryRequirementRepository;
        this.productAttributeRepository = productAttributeRepository;
        this.productRepository = productRepository;
        this.validator = validator;
    }

    /**
     * 카테고리에 요건이 없으면 규제 대상이 아니라는 뜻이다 - 404 가 아니라 빈 요건을 돌려준다.
     * 폼이 "요건 없음"과 "카테고리 없음"을 구분해 처리하지 않아도 되게 하기 위함이다.
     */
    public CategoryRequirementResponse getRequirement(Long categoryId) {
        return categoryRequirementRepository.findByCategoryId(categoryId)
                .map(this::toResponse)
                .orElseGet(() -> new CategoryRequirementResponse(categoryId, List.of(), List.of(), null, false));
    }

    public List<ProductAttributeResponse> listAttributes(Long productId) {
        return productAttributeRepository.findByProductId(productId).stream()
                .map(a -> new ProductAttributeResponse(a.getAttributeCode(), a.getAttributeValue()))
                .toList();
    }

    /**
     * 전량 교체(upsert 가 아니라 replace)다. 부분 갱신으로 두면 폼이 항목을 지웠을 때
     * 지워졌다는 사실이 전달되지 않아 옛 값이 남는다.
     */
    @Transactional
    public List<ProductAttributeResponse> replaceAttributes(Long productId, List<ProductAttributeValue> values) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new NoSuchElementException("product not found: " + productId));
        productAttributeRepository.deleteByProductId(productId);
        productAttributeRepository.flush();
        values.forEach(v -> productAttributeRepository.save(
                new ProductAttribute(product, v.code(), v.value())));
        return values.stream().map(v -> new ProductAttributeResponse(v.code(), v.value())).toList();
    }

    private CategoryRequirementResponse toResponse(CategoryRequirement requirement) {
        List<RequiredAttributeResponse> attributes = validator
                .requiredAttributesFor(requirement.getCategory().getId()).stream()
                .map(a -> new RequiredAttributeResponse(a.code(), a.label(), a.required()))
                .toList();
        return new CategoryRequirementResponse(
                requirement.getCategory().getId(),
                attributes,
                parseDocuments(requirement),
                requirement.getCommissionRate(),
                requirement.isRestricted());
    }

    private List<String> parseDocuments(CategoryRequirement requirement) {
        try {
            return objectMapper.readValue(requirement.getRequiredDocuments(), new TypeReference<List<String>>() { });
        } catch (Exception e) {
            throw new IllegalStateException(
                    "카테고리 인허가 서류 정의가 손상되었습니다(categoryId="
                            + requirement.getCategory().getId() + "). 관리자 확인이 필요합니다.", e);
        }
    }
}
