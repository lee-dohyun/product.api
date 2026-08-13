package com.dh.product.service;

import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.product.domain.Category;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductImage;
import com.dh.product.dto.ProductDtos.CategoryResponse;
import com.dh.product.dto.ProductDtos.ProductCreateRequest;
import com.dh.product.dto.ProductDtos.ProductImageResponse;
import com.dh.product.dto.ProductDtos.ProductResponse;
import com.dh.product.dto.ProductDtos.ProductSummaryResponse;
import com.dh.product.dto.ProductDtos.ProductUpdateRequest;
import com.dh.product.repository.CategoryRepository;
import com.dh.product.repository.ProductRepository;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private static final String PRODUCT_CACHE = "product";

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final InventoryService inventoryService;

    public ProductService(
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            InventoryService inventoryService) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.inventoryService = inventoryService;
    }

    public List<ProductSummaryResponse> listProducts(Long categoryId, String q) {
        List<Product> products;
        boolean hasCategory = categoryId != null;
        boolean hasQuery = q != null && !q.isBlank();

        if (hasCategory && hasQuery) {
            products = productRepository.findByCategoryIdAndNameContainingIgnoreCase(categoryId, q);
        } else if (hasCategory) {
            products = productRepository.findByCategoryId(categoryId);
        } else if (hasQuery) {
            products = productRepository.findByNameContainingIgnoreCase(q);
        } else {
            products = productRepository.findAll();
        }

        return products.stream()
                .map(p -> new ProductSummaryResponse(
                        p.getId(),
                        p.getCategory().getId(),
                        p.getName(),
                        p.getPrice(),
                        p.getStockQuantity(),
                        p.getImages().isEmpty() ? null : p.getImages().get(0).getImageUrl()))
                .toList();
    }

    // 단일 상품 조회만 Redis 캐싱 대상 (product:{id})
    @Cacheable(cacheNames = PRODUCT_CACHE, key = "#id")
    public ProductResponse getProduct(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("product not found: " + id));
        return toResponse(product);
    }

    @Transactional
    public ProductResponse createProduct(ProductCreateRequest request) {
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new NoSuchElementException("category not found: " + request.categoryId()));

        Product product = new Product();
        product.setCategory(category);
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStockQuantity(request.stockQuantity());

        if (request.imageUrls() != null) {
            short order = 0;
            for (String url : request.imageUrls()) {
                ProductImage image = new ProductImage();
                image.setImageUrl(url);
                image.setSortOrder(order++);
                product.addImage(image);
            }
        }

        Product saved = productRepository.save(product);
        inventoryService.initialize(saved, request.stockQuantity());
        return toResponse(saved);
    }

    @Transactional
    @CacheEvict(cacheNames = PRODUCT_CACHE, key = "#id")
    public ProductResponse updateProduct(Long id, ProductUpdateRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("product not found: " + id));
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new NoSuchElementException("category not found: " + request.categoryId()));

        product.setCategory(category);
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        inventoryService.adjustTo(product, request.stockQuantity());

        product.getImages().clear();
        if (request.imageUrls() != null) {
            short order = 0;
            for (String url : request.imageUrls()) {
                ProductImage image = new ProductImage();
                image.setImageUrl(url);
                image.setSortOrder(order++);
                product.addImage(image);
            }
        }

        return toResponse(product);
    }

    @Transactional
    @CacheEvict(cacheNames = PRODUCT_CACHE, key = "#id")
    public void deleteProduct(Long id) {
        if (!productRepository.existsById(id)) {
            throw new NoSuchElementException("product not found: " + id);
        }
        inventoryService.deleteForProduct(id);
        productRepository.deleteById(id);
    }

    private ProductResponse toResponse(Product product) {
        List<ProductImageResponse> images = product.getImages().stream()
                .map(i -> new ProductImageResponse(i.getId(), i.getImageUrl(), i.getSortOrder()))
                .toList();

        return new ProductResponse(
                product.getId(),
                toCategoryResponse(product.getCategory()),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStockQuantity(),
                images,
                product.getCreatedAt(),
                product.getUpdatedAt());
    }

    private CategoryResponse toCategoryResponse(Category category) {
        Long parentId = category.getParent() != null ? category.getParent().getId() : null;
        return new CategoryResponse(category.getId(), category.getName(), parentId);
    }
}
