package com.dh.product.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.dh.product.dto.SubmissionDtos.CategoryRequirementResponse;
import com.dh.product.dto.SubmissionDtos.ProductAttributeResponse;
import com.dh.product.dto.SubmissionDtos.ProductAttributeUpsertRequest;
import com.dh.product.service.submission.ProductAttributeService;

import jakarta.validation.Valid;

/**
 * 상품 고시 항목 입력과 카테고리 요건 조회(product.api#30).
 *
 * <p>경로가 {@code /api/products/**} 와 {@code /api/categories/**} 아래라
 * {@code AdminAuthInterceptor} 의 기존 PRODUCT_MANAGER 규칙이 그대로 적용된다 -
 * 새 프리픽스를 만들지 않은 것은 의도다(인터셉터 등록 누락으로 인증이 통째로 빠지는
 * 사고를 피하기 위해, 이미 보호되는 프리픽스 안에 둔다).
 */
@RestController
public class ProductAttributeController {

    private final ProductAttributeService productAttributeService;

    public ProductAttributeController(ProductAttributeService productAttributeService) {
        this.productAttributeService = productAttributeService;
    }

    @GetMapping("/api/categories/{categoryId}/requirement")
    public CategoryRequirementResponse getRequirement(@PathVariable Long categoryId) {
        return productAttributeService.getRequirement(categoryId);
    }

    @GetMapping("/api/products/{productId}/attributes")
    public List<ProductAttributeResponse> listAttributes(@PathVariable Long productId) {
        return productAttributeService.listAttributes(productId);
    }

    @PutMapping("/api/products/{productId}/attributes")
    public List<ProductAttributeResponse> replaceAttributes(
            @PathVariable Long productId, @Valid @RequestBody ProductAttributeUpsertRequest request) {
        return productAttributeService.replaceAttributes(productId, request.attributes());
    }
}
