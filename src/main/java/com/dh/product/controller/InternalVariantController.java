package com.dh.product.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dh.product.dto.ProductDtos.VariantResolveResponse;
import com.dh.product.service.ProductService;

// order.api가 주문 생성 시 가격을 확정하려고 클러스터 내부망으로만 호출한다. /api/internal/**은
// 게이트웨이에 라우트가 없어 외부에서 도달 불가능하다(InventoryController와 같은 신뢰 경계).
// 가격을 클라이언트가 정하던 문제를 막기 위한 것이므로, 이 응답이 주문 금액의 유일한 출처다 —
// Redmine posselect #232.
@RestController
@RequestMapping("/internal/variants")
public class InternalVariantController {

    private final ProductService productService;

    public InternalVariantController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/resolve")
    public List<VariantResolveResponse> resolve(@RequestParam("ids") List<Long> ids) {
        return productService.resolveVariants(ids);
    }
}
