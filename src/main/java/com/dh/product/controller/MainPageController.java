package com.dh.product.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dh.product.dto.ProductDtos.ProductSummaryResponse;
import com.dh.product.service.MainPageService;

@RestController
@RequestMapping("/api/products/main")
public class MainPageController {

    private final MainPageService mainPageService;

    public MainPageController(MainPageService mainPageService) {
        this.mainPageService = mainPageService;
    }

    @GetMapping("/best")
    public List<ProductSummaryResponse> getBestProducts(@RequestParam(defaultValue = "10") int limit) {
        return mainPageService.getBestProducts(Math.min(limit, 20));
    }

    @GetMapping("/new")
    public List<ProductSummaryResponse> getNewProducts(@RequestParam(defaultValue = "10") int limit) {
        return mainPageService.getNewProducts(Math.min(limit, 20));
    }

    @GetMapping("/by-category")
    public Map<Long, List<ProductSummaryResponse>> getProductsByCategory() {
        return mainPageService.getProductsByCategory();
    }
}
