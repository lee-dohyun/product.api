package com.dh.product.controller;

import com.dh.product.dto.BannerDtos.BannerAdminResponse;
import com.dh.product.dto.BannerDtos.BannerCreateRequest;
import com.dh.product.dto.BannerDtos.BannerUpdateRequest;
import com.dh.product.service.AdminBannerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/banners")
public class AdminBannerController {

    private final AdminBannerService adminBannerService;

    @GetMapping
    public List<BannerAdminResponse> getAllBanners() {
        log.info("[AdminBannerController/getAllBanners] 관리자 배너 전체 목록 조회 요청");
        return adminBannerService.getAllBanners();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BannerAdminResponse createBanner(@RequestBody BannerCreateRequest request) {
        log.info("[AdminBannerController/createBanner] 관리자 배너 생성 요청: {}", request.title());
        return adminBannerService.createBanner(request);
    }

    @PutMapping("/{id}")
    public BannerAdminResponse updateBanner(@PathVariable("id") Long id, @RequestBody BannerUpdateRequest request) {
        log.info("[AdminBannerController/updateBanner] 관리자 배너 수정 요청: id={}", id);
        return adminBannerService.updateBanner(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBanner(@PathVariable("id") Long id) {
        log.info("[AdminBannerController/deleteBanner] 관리자 배너 삭제 요청: id={}", id);
        adminBannerService.deleteBanner(id);
    }
}
