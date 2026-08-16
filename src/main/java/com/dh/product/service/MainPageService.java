package com.dh.product.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.product.domain.Category;
import com.dh.product.domain.Inventory;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductVariant;
import com.dh.product.dto.ProductDtos.ProductSummaryResponse;
import com.dh.product.repository.CategoryRepository;
import com.dh.product.repository.InventoryRepository;
import com.dh.product.repository.ProductRepository;
import com.dh.product.repository.ProductVariantRepository;

@Service
@Transactional(readOnly = true)
public class MainPageService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductVariantRepository productVariantRepository;
    private final InventoryRepository inventoryRepository;

    public MainPageService(
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            ProductVariantRepository productVariantRepository,
            InventoryRepository inventoryRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.productVariantRepository = productVariantRepository;
        this.inventoryRepository = inventoryRepository;
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
