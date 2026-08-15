package com.dh.product.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.product.domain.Category;
import com.dh.product.domain.Inventory;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductImage;
import com.dh.product.domain.ProductOption;
import com.dh.product.domain.ProductOptionValue;
import com.dh.product.domain.ProductVariant;
import com.dh.product.dto.ProductDtos.CategoryResponse;
import com.dh.product.dto.ProductDtos.CreateOptionRequest;
import com.dh.product.dto.ProductDtos.CreateOptionValueRequest;
import com.dh.product.dto.ProductDtos.CreateVariantRequest;
import com.dh.product.dto.ProductDtos.OptionResponse;
import com.dh.product.dto.ProductDtos.OptionValueResponse;
import com.dh.product.dto.ProductDtos.ProductCreateRequest;
import com.dh.product.dto.ProductDtos.ProductImageResponse;
import com.dh.product.dto.ProductDtos.ProductResponse;
import com.dh.product.dto.ProductDtos.ProductSummaryResponse;
import com.dh.product.dto.ProductDtos.ProductUpdateRequest;
import com.dh.product.dto.ProductDtos.UpdateVariantRequest;
import com.dh.product.dto.ProductDtos.VariantOptionValueResponse;
import com.dh.product.dto.ProductDtos.VariantResolveResponse;
import com.dh.product.dto.ProductDtos.VariantResponse;
import com.dh.product.repository.CategoryRepository;
import com.dh.product.repository.InventoryRepository;
import com.dh.product.repository.ProductOptionRepository;
import com.dh.product.repository.ProductOptionValueRepository;
import com.dh.product.repository.ProductRepository;
import com.dh.product.repository.ProductVariantRepository;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private static final String PRODUCT_CACHE = "product";

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductVariantRepository productVariantRepository;
    private final ProductOptionRepository productOptionRepository;
    private final ProductOptionValueRepository productOptionValueRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryService inventoryService;

    public ProductService(
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            ProductVariantRepository productVariantRepository,
            ProductOptionRepository productOptionRepository,
            ProductOptionValueRepository productOptionValueRepository,
            InventoryRepository inventoryRepository,
            InventoryService inventoryService) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.productVariantRepository = productVariantRepository;
        this.productOptionRepository = productOptionRepository;
        this.productOptionValueRepository = productOptionValueRepository;
        this.inventoryRepository = inventoryRepository;
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

        if (products.isEmpty()) {
            return List.of();
        }

        List<Long> productIds = products.stream().map(Product::getId).toList();
        Map<Long, List<ProductVariant>> variantsByProduct = productVariantRepository.findByProductIdIn(productIds)
                .stream()
                .collect(Collectors.groupingBy(v -> v.getProduct().getId()));
        Map<Long, Integer> stockByVariant = stockByVariantId(variantsByProduct.values().stream()
                .flatMap(List::stream)
                .map(ProductVariant::getId)
                .toList());

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
        addImages(product, request.imageUrls());

        Product saved = productRepository.save(product);

        ProductVariant defaultVariant = new ProductVariant(saved, null, request.price());
        productVariantRepository.save(defaultVariant);
        inventoryService.initialize(defaultVariant, request.stockQuantity());

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

        product.getImages().clear();
        addImages(product, request.imageUrls());

        ProductVariant defaultVariant = productVariantRepository.findByProductId(id).stream()
                .min(Comparator.comparing(ProductVariant::getId))
                .orElseThrow(() -> new IllegalStateException("상품에 variant가 없습니다: " + id));
        defaultVariant.setPrice(request.price());
        inventoryService.adjustTo(defaultVariant, request.stockQuantity());

        return toResponse(product);
    }

    @Transactional
    @CacheEvict(cacheNames = PRODUCT_CACHE, key = "#id")
    public void deleteProduct(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("product not found: " + id));
        // inventory_transactions는 DB cascade 대상이 아니라 먼저 애플리케이션에서 정리해야
        // 이어지는 product -> variants -> inventories cascade 삭제가 FK 위반 없이 끝난다.
        productVariantRepository.findByProductId(id)
                .forEach(v -> inventoryService.deleteForVariant(v.getId()));
        productRepository.delete(product);
    }

    @Transactional
    public OptionResponse createOption(Long productId, CreateOptionRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new NoSuchElementException("product not found: " + productId));
        ProductOption option = new ProductOption(product, request.name());
        productOptionRepository.save(option);
        return toOptionResponse(option);
    }

    @Transactional
    public OptionValueResponse addOptionValue(Long productId, Long optionId, CreateOptionValueRequest request) {
        ProductOption option = findOptionOrThrow(productId, optionId);
        ProductOptionValue value = new ProductOptionValue(option, request.value());
        productOptionValueRepository.save(value);
        return new OptionValueResponse(value.getId(), value.getValue());
    }

    @Transactional
    @CacheEvict(cacheNames = PRODUCT_CACHE, key = "#productId")
    public VariantResponse createVariant(Long productId, CreateVariantRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new NoSuchElementException("product not found: " + productId));

        ProductVariant variant = new ProductVariant(product, request.sku(), request.price());
        if (request.optionValueIds() != null && !request.optionValueIds().isEmpty()) {
            variant.getOptionValues().addAll(productOptionValueRepository.findAllById(request.optionValueIds()));
        }
        productVariantRepository.save(variant);
        inventoryService.initialize(variant, request.stockQuantity());

        return toVariantResponse(variant, request.stockQuantity());
    }

    @Transactional
    @CacheEvict(cacheNames = PRODUCT_CACHE, key = "#productId")
    public VariantResponse updateVariant(Long productId, Long variantId, UpdateVariantRequest request) {
        ProductVariant variant = findVariantOrThrow(productId, variantId);
        variant.setSku(request.sku());
        variant.setPrice(request.price());
        variant.setActive(request.active());
        inventoryService.adjustTo(variant, request.stockQuantity());
        return toVariantResponse(variant, request.stockQuantity());
    }

    @Transactional
    @CacheEvict(cacheNames = PRODUCT_CACHE, key = "#productId")
    public void deleteVariant(Long productId, Long variantId) {
        ProductVariant variant = findVariantOrThrow(productId, variantId);
        inventoryService.deleteForVariant(variantId);
        productVariantRepository.delete(variant);
    }

    public List<VariantResponse> listVariants(Long productId) {
        List<ProductVariant> variants = productVariantRepository.findByProductId(productId);
        Map<Long, Integer> stock = stockByVariantId(variants.stream().map(ProductVariant::getId).toList());
        return variants.stream().map(v -> toVariantResponse(v, stock.getOrDefault(v.getId(), 0))).toList();
    }

    /**
     * variantId만으로 상품/가격을 확정해 돌려준다. 존재하지 않는 id는 결과에서 빠지므로
     * 호출자가 요청한 개수와 대조해 누락을 판정해야 한다.
     */
    public List<VariantResolveResponse> resolveVariants(Collection<Long> variantIds) {
        if (variantIds == null || variantIds.isEmpty()) {
            return List.of();
        }
        return productVariantRepository.findAllByIdWithProduct(variantIds).stream()
                .map(v -> new VariantResolveResponse(
                        v.getId(),
                        v.getProduct().getId(),
                        v.getProduct().getName(),
                        v.getPrice(),
                        v.isActive()))
                .toList();
    }

    private ProductOption findOptionOrThrow(Long productId, Long optionId) {
        return productOptionRepository.findById(optionId)
                .filter(o -> o.getProduct().getId().equals(productId))
                .orElseThrow(() -> new NoSuchElementException("option not found: " + optionId));
    }

    private ProductVariant findVariantOrThrow(Long productId, Long variantId) {
        return productVariantRepository.findById(variantId)
                .filter(v -> v.getProduct().getId().equals(productId))
                .orElseThrow(() -> new NoSuchElementException("variant not found: " + variantId));
    }

    private void addImages(Product product, List<String> imageUrls) {
        if (imageUrls == null) {
            return;
        }
        short order = 0;
        for (String url : imageUrls) {
            ProductImage image = new ProductImage();
            image.setImageUrl(url);
            image.setSortOrder(order++);
            product.addImage(image);
        }
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

    private ProductResponse toResponse(Product product) {
        List<ProductImageResponse> images = product.getImages().stream()
                .map(i -> new ProductImageResponse(i.getId(), i.getImageUrl(), i.getSortOrder()))
                .toList();

        List<ProductVariant> variants = productVariantRepository.findByProductId(product.getId());
        Map<Long, Integer> stock = stockByVariantId(variants.stream().map(ProductVariant::getId).toList());
        List<VariantResponse> variantResponses = variants.stream()
                .map(v -> toVariantResponse(v, stock.getOrDefault(v.getId(), 0)))
                .toList();

        List<OptionResponse> optionResponses = product.getOptions().stream()
                .map(this::toOptionResponse)
                .toList();

        return new ProductResponse(
                product.getId(),
                toCategoryResponse(product.getCategory()),
                product.getName(),
                product.getDescription(),
                representativePrice(variants),
                totalStock(variants, stock),
                images,
                optionResponses,
                variantResponses,
                product.getCreatedAt(),
                product.getUpdatedAt());
    }

    private VariantResponse toVariantResponse(ProductVariant variant, int stockQuantity) {
        List<VariantOptionValueResponse> optionValues = variant.getOptionValues().stream()
                .map(ov -> new VariantOptionValueResponse(
                        ov.getOption().getId(), ov.getOption().getName(), ov.getId(), ov.getValue()))
                .toList();
        return new VariantResponse(
                variant.getId(), variant.getSku(), variant.getPrice(), variant.isActive(), stockQuantity, optionValues);
    }

    private OptionResponse toOptionResponse(ProductOption option) {
        List<OptionValueResponse> values = option.getValues().stream()
                .map(v -> new OptionValueResponse(v.getId(), v.getValue()))
                .toList();
        return new OptionResponse(option.getId(), option.getName(), values);
    }

    private CategoryResponse toCategoryResponse(Category category) {
        Long parentId = category.getParent() != null ? category.getParent().getId() : null;
        return new CategoryResponse(category.getId(), category.getName(), parentId);
    }
}