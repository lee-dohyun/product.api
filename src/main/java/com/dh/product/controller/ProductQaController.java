package com.dh.product.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dh.product.dto.ProductQaDtos.ProductQaRequest;
import com.dh.product.dto.ProductQaDtos.ProductQaResponse;
import com.dh.product.service.rag.ProductQaService;

import jakarta.validation.Valid;

/**
 * "이 상품 어때요?" 류 자연어 질의응답 (이직 포트폴리오 Track C, product.api#46).
 * 로그인 여부와 무관하게 열려 있는 상품 조회 기능이다.
 *
 * <p>다만 "/api/products/** 는 공개"가 아니다 — 그 경로의 GET 만 공개고 POST/PUT/DELETE 는
 * {@code AdminAuthInterceptor} 가 staff 전용으로 막는다. 이 컨트롤러는 POST 라서 처음 배포됐을 때
 * 고객이 403 을 받았다(product.api#58). {@code WebConfig} 의 excludePathPatterns 에 이 경로가
 * 있어야 동작하므로, 경로를 바꾸면 그쪽도 같이 바꿀 것.
 */
@RestController
@RequestMapping("/api/products/qa")
public class ProductQaController {

    private final ProductQaService productQaService;

    public ProductQaController(ProductQaService productQaService) {
        this.productQaService = productQaService;
    }

    @PostMapping
    public ProductQaResponse ask(@Valid @RequestBody ProductQaRequest request) {
        return productQaService.answer(request.question());
    }
}
