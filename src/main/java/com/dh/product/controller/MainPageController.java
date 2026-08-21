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

    /**
     * 대분류별 최신 상품 목록. 키는 대분류 id 를 <b>문자열로</b> 담는다 - JSON 오브젝트의 키는
     * 어차피 문자열이라, Long 으로 선언하면 Redis 캐시를 왕복한 값이 선언 타입과 어긋나
     * 응답을 쓸 때 터진다(product.api#33). 클라이언트는 이미 문자열 키로 다루고 있다.
     */
    @GetMapping("/by-category")
    public Map<String, List<ProductSummaryResponse>> getProductsByCategory() {
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
