package com.dh.product.service;

import com.dh.product.config.CacheNames;
import com.dh.product.domain.Banner;
import com.dh.product.dto.BannerDtos.BannerAdminResponse;
import com.dh.product.dto.BannerDtos.BannerCreateRequest;
import com.dh.product.dto.BannerDtos.BannerUpdateRequest;
import com.dh.product.repository.BannerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminBannerService {

    private final BannerRepository bannerRepository;

    @Transactional(readOnly = true)
    public List<BannerAdminResponse> getAllBanners() {
        log.info("[AdminBannerService/getAllBanners] 관리자 배너 전체 조회");
        List<Banner> banners = bannerRepository.findAll();
        return banners.stream()
                .map(this::toAdminResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    @CacheEvict(cacheNames = CacheNames.MAIN_BANNERS, allEntries = true)
    public BannerAdminResponse createBanner(BannerCreateRequest request) {
        log.info("[AdminBannerService/createBanner] 배너 생성: {}", request.title());
        Banner banner = Banner.builder()
                .title(request.title())
                .subtitle(request.subtitle())
                .imageUrl(request.imageUrl())
                .link(request.link())
                .bgColor(request.bgColor())
                .sortOrder(request.sortOrder())
                .isActive(request.isActive())
                .build();
        
        Banner saved = bannerRepository.save(banner);
        return toAdminResponse(saved);
    }

    @Transactional
    @CacheEvict(cacheNames = CacheNames.MAIN_BANNERS, allEntries = true)
    public BannerAdminResponse updateBanner(Long id, BannerUpdateRequest request) {
        log.info("[AdminBannerService/updateBanner] 배너 수정: id={}", id);
        Banner banner = bannerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Banner not found: " + id));
        
        banner.update(
                request.title(),
                request.subtitle(),
                request.imageUrl(),
                request.link(),
                request.bgColor(),
                request.sortOrder(),
                request.isActive()
        );
        
        return toAdminResponse(banner);
    }

    @Transactional
    @CacheEvict(cacheNames = CacheNames.MAIN_BANNERS, allEntries = true)
    public void deleteBanner(Long id) {
        log.info("[AdminBannerService/deleteBanner] 배너 삭제: id={}", id);
        Banner banner = bannerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Banner not found: " + id));
        bannerRepository.delete(banner);
    }

    private BannerAdminResponse toAdminResponse(Banner b) {
        return new BannerAdminResponse(
                b.getId(),
                b.getTitle(),
                b.getSubtitle(),
                b.getImageUrl(),
                b.getLink(),
                b.getBgColor(),
                b.getSortOrder(),
                b.getIsActive()
        );
    }
}
