package com.dh.product.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.product.domain.Banner;
import com.dh.product.domain.Category;
import com.dh.product.domain.Inventory;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductVariant;
import com.dh.product.dto.BannerDtos.BannerResponse;
import com.dh.product.dto.ProductDtos.ProductSummaryResponse;
import com.dh.product.repository.BannerRepository;
import com.dh.product.repository.CategoryRepository;
import com.dh.product.repository.InventoryRepository;
import com.dh.product.repository.ProductRepository;
import com.dh.product.repository.ProductVariantRepository;

@Service
@Transactional(readOnly = true)
public class MainPageService {

    private static final Logger log = LoggerFactory.getLogger(MainPageService.class);

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductVariantRepository productVariantRepository;
    private final InventoryRepository inventoryRepository;
    private final BannerRepository bannerRepository;

    public MainPageService(
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            ProductVariantRepository productVariantRepository,
            InventoryRepository inventoryRepository,
            BannerRepository bannerRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.productVariantRepository = productVariantRepository;
        this.inventoryRepository = inventoryRepository;
        this.bannerRepository = bannerRepository;
    }

    @Cacheable(cacheNames = "main-best")
    public List<ProductSummaryResponse> getBestProducts(int limit) {
        List<Product> products = productRepository.findByOrderByIdDesc(PageRequest.of(0, limit));
        return toSummaryResponses(products);
    }

    @Cacheable(cacheNames = "main-new")
    public List<ProductSummaryResponse> getNewProducts(int limit) {
        List<Product> products = productRepository.findByOrderByCreatedAtDesc(PageRequest.of(0, limit));
        return toSummaryResponses(products);
    }

    @Cacheable(cacheNames = "main-by-category")
    public Map<Long, List<ProductSummaryResponse>> getProductsByCategory() {
        // 모든 최상위 카테고리에 대해 최신 상품 2개씩 매핑
        List<Category> categories = categoryRepository.findAll().stream()
                .filter(c -> c.getParent() == null)
                .toList();

        return categories.stream().collect(Collectors.toMap(
                Category::getId,
                c -> {
                    List<Product> products = productRepository.findByCategoryId(c.getId()).stream()
                            .sorted(Comparator.comparing(Product::getCreatedAt).reversed())
                            .limit(2)
                            .toList();
                    return toSummaryResponses(products);
                }
        ));
    }

    /**
     * 메인 페이지 프로모션 배너 목록을 반환합니다.
     */
    public List<BannerResponse> getBanners() {
        log.info("[MainPageService/getBanners] 메인 페이지 배너 DB 조회 요청");
        List<Banner> banners = bannerRepository.findAllByIsActiveTrueOrderBySortOrderAsc();
        return banners.stream()
                .map(b -> new BannerResponse(
                        b.getId(),
                        b.getTitle(),
                        b.getSubtitle(),
                        b.getImageUrl(),
                        b.getLink(),
                        b.getBgColor()
                ))
                .toList();
    }

    private List<ProductSummaryResponse> toSummaryResponses(List<Product> products) {
        if (products.isEmpty()) {
            return List.of();
        }

        List<Long> productIds = products.stream().map(Product::getId).toList();
        Map<Long, List<ProductVariant>> variantsByProduct = productVariantRepository.findByProductIdIn(productIds)
                .stream()
                .collect(Collectors.groupingBy(v -> v.getProduct().getId()));

        List<Long> variantIds = variantsByProduct.values().stream()
                .flatMap(List::stream)
                .map(ProductVariant::getId)
                .toList();

        Map<Long, Integer> stockByVariant = stockByVariantId(variantIds);

        return products.stream()
                .map(p -> {
                    List<ProductVariant> variants = variantsByProduct.getOrDefault(p.getId(), List.of());
                    return new ProductSummaryResponse(
                            p.getId(),
                            p.getCategory().getId(),
                            p.getName(),
                            representativePrice(variants),
                            totalStock(variants, stockByVariant),
                            p.getImages().isEmpty() ? null : p.getImages().get(0).getImageUrl());
                })
                .toList();
    }

    private Map<Long, Integer> stockByVariantId(List<Long> variantIds) {
        if (variantIds.isEmpty()) {
            return Map.of();
        }
        return inventoryRepository.findByVariantIdIn(variantIds).stream()
                .collect(Collectors.toMap(inv -> inv.getVariant().getId(), Inventory::getQuantity));
    }

    private BigDecimal representativePrice(List<ProductVariant> variants) {
        return variants.stream()
                .filter(ProductVariant::isActive)
                .map(ProductVariant::getPrice)
                .min(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
    }

    private int totalStock(List<ProductVariant> variants, Map<Long, Integer> stockByVariant) {
        return variants.stream()
                .filter(ProductVariant::isActive)
                .mapToInt(v -> stockByVariant.getOrDefault(v.getId(), 0))
                .sum();
    }
}
