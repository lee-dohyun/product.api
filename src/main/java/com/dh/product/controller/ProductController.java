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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dh.product.config.HiddenProductAccess;
import com.dh.product.dto.ProductDtos.ProductCreateRequest;
import com.dh.product.dto.ProductDtos.ProductResponse;
import com.dh.product.dto.ProductDtos.ProductSummaryResponse;
import com.dh.product.dto.ProductDtos.ProductUpdateRequest;
import com.dh.product.service.ProductService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final HiddenProductAccess hiddenProductAccess;

    public ProductController(ProductService productService, HiddenProductAccess hiddenProductAccess) {
        this.productService = productService;
        this.hiddenProductAccess = hiddenProductAccess;
    }

    /**
     * 공개 목록은 LIVE 만 준다(product.api#74). 관리자 화면(admin.front)이 staff 토큰을 실어 부르면
     * 임시저장·검수 중·판매중지 상품까지 전부 준다.
     */
    @GetMapping
    public List<ProductSummaryResponse> list(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String q,
            HttpServletRequest request) {
        return productService.listProducts(categoryId, q, hiddenProductAccess.canSeeHidden(request));
    }

    /**
     * 캐시(product:{id})에는 상태와 무관하게 들어가고 노출 판정은 캐시 뒤에서 한다 - 캐시 키를
     * 호출자별로 나누지 않기 위해서다.
     */
    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id, HttpServletRequest request) {
        ProductResponse product = productService.getProduct(id);
        hiddenProductAccess.requireVisible(id, product.status(), request);
        return product;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.createProduct(request));
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductUpdateRequest request) {
        return productService.updateProduct(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }
}
