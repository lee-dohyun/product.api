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
 * 로그인 여부와 무관하게 열려 있는 상품 조회 기능이라 다른 /api/products/** 와 동일하게 공개.
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
