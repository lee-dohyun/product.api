package com.dh.product.controller;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dh.product.dto.BannerDtos.BannerResponse;
import com.dh.product.dto.ProductDtos.ProductSummaryResponse;
import com.dh.product.service.MainPageService;

@RestController
@RequestMapping("/api/products/main")
public class MainPageController {

    private static final Logger log = LoggerFactory.getLogger(MainPageController.class);

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

    /**
     * 메인 페이지 상단에 노출될 프로모션 배너 목록을 반환합니다.
     */
    @GetMapping("/banners")
    public List<BannerResponse> getBanners() {
        log.info("[MainPageController/getBanners] 클라이언트의 메인 페이지 배너 API 호출");
        return mainPageService.getBanners();
    }
}
