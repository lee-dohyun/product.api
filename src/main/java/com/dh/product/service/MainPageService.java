package com.dh.product.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;

import com.dh.product.config.CacheNames;
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

    /**
     * 베스트 상품 목록 반환.
     * <p>현재는 실제 판매량 상위가 아니라 단순 ID 역순으로 반환되어 신상품(getNewProducts)과
     * 동일한 목록이 반환되는 기술 부채가 있습니다.
     * <p>TODO: Phase 4에서 order.api의 주문 항목을 N일 롤링으로 집계해 실제 판매량 상위로 교체해야 합니다. (이슈: lee-dohyun/product.api#26)
     */
    @Cacheable(cacheNames = CacheNames.MAIN_BEST)
    public List<ProductSummaryResponse> getBestProducts(int limit) {
        List<Product> products = productRepository.findByOrderByIdDesc(PageRequest.of(0, limit));
        return toSummaryResponses(products);
    }

    @Cacheable(cacheNames = CacheNames.MAIN_NEW)
    public List<ProductSummaryResponse> getNewProducts(int limit) {
        List<Product> products = productRepository.findByOrderByCreatedAtDesc(PageRequest.of(0, limit));
        return toSummaryResponses(products);
    }

    /** 카테고리별 영역에서 대분류 하나당 보여줄 상품 수. */
    private static final int PER_CATEGORY_LIMIT = 2;

    /**
     * 대분류별 최신 상품 목록.
     *
     * <p>키가 {@code Long}이 아니라 {@code String}인 것은 Redis 캐시 왕복 때문이다 - JSON
     * 오브젝트의 키는 항상 문자열이라 {@code Map<Long, ...>}으로 선언하면 캐시 히트 시
     * 선언 타입과 실제 타입이 어긋나 응답을 쓸 때 터진다(product.api#33).
     *
     * <p>상품은 <b>중분류</b>에 달리므로 대분류 id 로만 조회하면 아무것도 안 나온다.
     * 카탈로그가 2뎁스로 채워지자 이 영역이 통째로 비어버렸다 - 그전엔 상품이 1건뿐이었고
     * 그게 우연히 대분류에 직접 달려 있어서 드러나지 않았다.
     */
    @Cacheable(cacheNames = CacheNames.MAIN_BY_CATEGORY)
    public Map<String, List<ProductSummaryResponse>> getProductsByCategory() {
        List<Category> all = categoryRepository.findAll();
        if (all.isEmpty()) {
            return Map.of();
        }

        // 카테고리 id -> 그 카테고리가 속한 대분류 id (대분류 자신도 포함)
        Map<Long, Long> rootByCategoryId = new HashMap<>();
        for (Category c : all) {
            Long rootId = resolveRootId(c);
            if (rootId != null) {
                rootByCategoryId.put(c.getId(), rootId);
            }
        }

        // 대분류마다 따로 조회하지 않고 한 번에 가져와 묶는다.
        Map<Long, List<Product>> productsByRoot = productRepository.findByCategoryIdIn(rootByCategoryId.keySet())
                .stream()
                .collect(Collectors.groupingBy(p -> rootByCategoryId.get(p.getCategory().getId())));

        Map<Long, List<Product>> picked = new LinkedHashMap<>();
        for (Category root : all) {
            if (root.getParent() != null) {
                continue;
            }
            picked.put(root.getId(), productsByRoot.getOrDefault(root.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(Product::getCreatedAt).reversed())
                    .limit(PER_CATEGORY_LIMIT)
                    .toList());
        }

        // 요약 변환은 전체를 모아 한 번만 한다 - 대분류마다 부르면 variant/재고 조회가
        // 대분류 수만큼 반복된다.
        Map<Long, ProductSummaryResponse> summaryById = toSummaryResponses(
                picked.values().stream().flatMap(List::stream).toList()).stream()
                .collect(Collectors.toMap(ProductSummaryResponse::id, summary -> summary));

        Map<String, List<ProductSummaryResponse>> result = new LinkedHashMap<>();
        picked.forEach((rootId, products) -> result.put(
                String.valueOf(rootId),
                products.stream().map(p -> summaryById.get(p.getId())).filter(Objects::nonNull).toList()));
        return result;
    }

    /**
     * 카테고리가 속한 대분류 id. 도메인상 2뎁스만 쓰지만({@code Category} 주석), 데이터가
     * 어긋나 더 깊어지거나 순환하더라도 여기서 멈추도록 상한을 둔다.
     */
    private Long resolveRootId(Category category) {
        Category current = category;
        for (int depth = 0; depth < 10 && current != null; depth++) {
            if (current.getParent() == null) {
                return current.getId();
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * 메인 페이지 프로모션 배너 목록을 반환합니다.
     */
    @Cacheable(cacheNames = CacheNames.MAIN_BANNERS)
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
