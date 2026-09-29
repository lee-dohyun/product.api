package com.dh.product.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dh.product.config.PartnerAuthInterceptor;
import com.dh.product.config.PartnerPrincipal;
import com.dh.product.dto.PartnerDtos.PartnerOptionsRequest;
import com.dh.product.dto.PartnerDtos.PartnerProductRequest;
import com.dh.product.dto.PartnerDtos.PartnerVariantRequest;
import com.dh.product.dto.ProductDtos.VariantResponse;
import com.dh.product.dto.PartnerDtos.PartnerProductSummary;
import com.dh.product.dto.ProductDtos.ProductResponse;
import com.dh.product.dto.SubmissionDtos.CategoryRequirementResponse;
import com.dh.product.dto.SubmissionDtos.ProductAttributeResponse;
import com.dh.product.dto.SubmissionDtos.ProductAttributeUpsertRequest;
import com.dh.product.dto.SubmissionDtos.SubmissionResponse;
import com.dh.product.dto.SubmissionDtos.SubmitAcceptedResponse;
import com.dh.product.service.partner.PartnerProductService;
import com.dh.product.service.submission.ProductAttributeService;
import com.dh.product.service.submission.ProductSubmissionService;
import com.dh.product.service.submission.SubmissionValidationPublisher;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * 파트너 포털(partner.posselect.com) 전용 API(product.api#75). 인증은 {@link PartnerAuthInterceptor}.
 *
 * <p>경로에 판매자 id 를 받지 않는다 - 어느 판매자인지는 항상 토큰이 정한다.
 */
@RestController
@RequestMapping("/api/partner")
public class PartnerController {

    private final PartnerProductService partnerProductService;
    private final ProductSubmissionService submissionService;
    private final SubmissionValidationPublisher validationPublisher;
    private final ProductAttributeService attributeService;

    public PartnerController(
            PartnerProductService partnerProductService,
            ProductSubmissionService submissionService,
            SubmissionValidationPublisher validationPublisher,
            ProductAttributeService attributeService) {
        this.partnerProductService = partnerProductService;
        this.submissionService = submissionService;
        this.validationPublisher = validationPublisher;
        this.attributeService = attributeService;
    }

    @GetMapping("/products")
    public List<PartnerProductSummary> list(HttpServletRequest request) {
        return partnerProductService.list(partner(request).sellerId());
    }

    @GetMapping("/products/{id}")
    public ProductResponse get(@PathVariable Long id, HttpServletRequest request) {
        return partnerProductService.get(partner(request).sellerId(), id);
    }

    @PostMapping("/products")
    public ResponseEntity<ProductResponse> create(
            @Valid @RequestBody PartnerProductRequest body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(partnerProductService.create(partner(request).sellerId(), body));
    }

    @PutMapping("/products/{id}")
    public ProductResponse update(
            @PathVariable Long id, @Valid @RequestBody PartnerProductRequest body, HttpServletRequest request) {
        return partnerProductService.update(partner(request).sellerId(), id, body);
    }

    /** 옵션 구성 — 모든 조합의 SKU 자동 생성(product.api#80). 이미 옵션이 있으면 409. */
    @PutMapping("/products/{id}/options")
    public ProductResponse configureOptions(
            @PathVariable Long id, @Valid @RequestBody PartnerOptionsRequest body, HttpServletRequest request) {
        return partnerProductService.configureOptions(partner(request).sellerId(), id, body);
    }

    /** SKU 한 개의 가격·재고·판매 여부. */
    @PutMapping("/products/{id}/variants/{variantId}")
    public VariantResponse updateVariant(
            @PathVariable Long id, @PathVariable Long variantId, @Valid @RequestBody PartnerVariantRequest body,
            HttpServletRequest request) {
        return partnerProductService.updateVariant(partner(request).sellerId(), id, variantId, body);
    }

    /** 카테고리가 요구하는 고시 항목. 공개 경로(/api/categories/{id}/requirement)와 같은 값 - 포털이 한 호스트로만 부르게. */
    @GetMapping("/categories/{categoryId}/requirement")
    public CategoryRequirementResponse requirement(@PathVariable Long categoryId) {
        return attributeService.getRequirement(categoryId);
    }

    @GetMapping("/products/{id}/attributes")
    public List<ProductAttributeResponse> attributes(@PathVariable Long id, HttpServletRequest request) {
        return partnerProductService.listAttributes(partner(request).sellerId(), id);
    }

    @PutMapping("/products/{id}/attributes")
    public List<ProductAttributeResponse> replaceAttributes(
            @PathVariable Long id, @Valid @RequestBody ProductAttributeUpsertRequest body,
            HttpServletRequest request) {
        return partnerProductService.replaceAttributes(partner(request).sellerId(), id, body.attributes());
    }

    /** 검수 제출(처음이면 제출, 보완 요청 상태면 재제출). 202 + 제출 id - 결과는 GET 으로 조회한다. */
    @PostMapping("/products/{id}/submission")
    public ResponseEntity<SubmitAcceptedResponse> submit(@PathVariable Long id, HttpServletRequest request) {
        PartnerPrincipal partner = partner(request);
        Long submissionId = partnerProductService.submit(partner.sellerId(), id, partner.actor());
        // 커밋 이후에 검증을 의뢰한다 - submit() 의 트랜잭션이 끝난 뒤다(ProductSubmissionService#submit 주석).
        validationPublisher.publish(submissionId);
        return ResponseEntity.accepted()
                .body(new SubmitAcceptedResponse(submissionId, submissionService.get(submissionId).getStatus().name()));
    }

    /** 가장 최근 검수 결과 + 입력칸별 이슈. 응답 조립 규칙은 PartnerProductService#latestSubmission. */
    @GetMapping("/products/{id}/submission")
    public SubmissionResponse latestSubmission(@PathVariable Long id, HttpServletRequest request) {
        PartnerPrincipal partner = partner(request);
        return partnerProductService.latestSubmission(partner.sellerId(), id, partner.actor());
    }

    private static PartnerPrincipal partner(HttpServletRequest request) {
        return (PartnerPrincipal) request.getAttribute(PartnerAuthInterceptor.PRINCIPAL_ATTRIBUTE);
    }
}
