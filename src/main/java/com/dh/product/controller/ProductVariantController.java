package com.dh.product.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dh.product.dto.ProductDtos.CreateOptionRequest;
import com.dh.product.dto.ProductDtos.CreateOptionValueRequest;
import com.dh.product.dto.ProductDtos.CreateVariantRequest;
import com.dh.product.dto.ProductDtos.OptionResponse;
import com.dh.product.dto.ProductDtos.OptionValueResponse;
import com.dh.product.dto.ProductDtos.UpdateVariantRequest;
import com.dh.product.dto.ProductDtos.VariantResponse;
import com.dh.product.service.ProductService;

import jakarta.validation.Valid;

// /api/products/**에 걸려있어 WebConfig의 AdminAuthInterceptor가 쓰기 요청(POST/PUT/DELETE)을
// admin.front(Keycloak staff realm) 전용으로 이미 막아준다 - GET만 공개.
@RestController
@RequestMapping("/api/products/{productId}")
public class ProductVariantController {

    private final ProductService productService;

    public ProductVariantController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping("/options")
    public ResponseEntity<OptionResponse> createOption(
            @PathVariable Long productId, @Valid @RequestBody CreateOptionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.createOption(productId, request));
    }

    @PostMapping("/options/{optionId}/values")
    public ResponseEntity<OptionValueResponse> addOptionValue(
            @PathVariable Long productId,
            @PathVariable Long optionId,
            @Valid @RequestBody CreateOptionValueRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productService.addOptionValue(productId, optionId, request));
    }

    @GetMapping("/variants")
    public List<VariantResponse> listVariants(@PathVariable Long productId) {
        return productService.listVariants(productId);
    }

    @PostMapping("/variants")
    public ResponseEntity<VariantResponse> createVariant(
            @PathVariable Long productId, @Valid @RequestBody CreateVariantRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.createVariant(productId, request));
    }

    @PutMapping("/variants/{variantId}")
    public VariantResponse updateVariant(
            @PathVariable Long productId,
            @PathVariable Long variantId,
            @Valid @RequestBody UpdateVariantRequest request) {
        return productService.updateVariant(productId, variantId, request);
    }

    @DeleteMapping("/variants/{variantId}")
    public ResponseEntity<Void> deleteVariant(@PathVariable Long productId, @PathVariable Long variantId) {
        productService.deleteVariant(productId, variantId);
        return ResponseEntity.noContent().build();
    }
}
