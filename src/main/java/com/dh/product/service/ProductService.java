package com.dh.product.service;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.cache.annotation.Cacheable;

import com.dh.product.config.CacheNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.product.domain.Category;
import com.dh.product.domain.Inventory;
import com.dh.product.domain.Offer;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductImage;
import com.dh.product.domain.ProductOption;
import com.dh.product.domain.ProductOptionValue;
import com.dh.product.domain.ProductPolicy;
import com.dh.product.domain.ProductStatus;
import com.dh.product.domain.ProductVariant;
import com.dh.product.domain.Seller;
import com.dh.product.domain.ShippingFeeType;
import com.dh.product.dto.ProductDtos.CategoryResponse;
import com.dh.product.dto.ProductDtos.CreateOptionRequest;
import com.dh.product.dto.ProductDtos.CreateOptionValueRequest;
import com.dh.product.dto.ProductDtos.CreateVariantRequest;
import com.dh.product.dto.ProductDtos.OptionResponse;
import com.dh.product.dto.ProductDtos.OptionValueResponse;
import com.dh.product.dto.ProductDtos.ProductCreateRequest;
import com.dh.product.dto.ProductDtos.ProductImageResponse;
import com.dh.product.dto.ProductDtos.ProductResponse;
import com.dh.product.dto.ProductDtos.ProductManagementSummary;
import com.dh.product.dto.SellerDtos.PublicSellerInfo;
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
import com.dh.product.service.offer.OfferService;
import com.dh.product.repository.SellerRepository;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private static final String PRODUCT_CACHE = CacheNames.PRODUCT;

    // 자사 판매자. V14 가 id=1 로 시드하고 order.api V6 의 order_items.seller_id=1 과 맞춰 둔 값이다
    // (product.api#29). 협력사 포털이 생기기 전까지 등록되는 상품은 전부 여기에 귀속된다.
    private static final Long FIRST_PARTY_SELLER_ID = 1L;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductVariantRepository productVariantRepository;
    private final ProductOptionRepository productOptionRepository;
    private final ProductOptionValueRepository productOptionValueRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryService inventoryService;
    private final SellerRepository sellerRepository;
    private final OfferService offerService;
    private final PurchaseRules purchaseRules;

    public ProductService(
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            ProductVariantRepository productVariantRepository,
            ProductOptionRepository productOptionRepository,
            ProductOptionValueRepository productOptionValueRepository,
            InventoryRepository inventoryRepository,
            InventoryService inventoryService,
            SellerRepository sellerRepository,
            OfferService offerService,
            PurchaseRules purchaseRules) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.productVariantRepository = productVariantRepository;
        this.productOptionRepository = productOptionRepository;
        this.productOptionValueRepository = productOptionValueRepository;
        this.inventoryRepository = inventoryRepository;
        this.inventoryService = inventoryService;
        this.sellerRepository = sellerRepository;
        this.offerService = offerService;
        this.purchaseRules = purchaseRules;
    }

    /** 공개 목록 - LIVE 상품만. */
    public List<ProductSummaryResponse> listProducts(Long categoryId, String q) {
        return listProducts(categoryId, q, false);
    }

    /**
     * @param includeHidden true 면 LIVE 가 아닌 상품(DRAFT/PAUSED/ARCHIVED)도 포함한다.
     *                      관리자 화면 전용 - 호출부(ProductController)가 staff 역할을 확인한 뒤에만 true 를 준다.
     */
    public List<ProductSummaryResponse> listProducts(Long categoryId, String q, boolean includeHidden) {
        List<Product> products;
        boolean hasCategory = categoryId != null;
        boolean hasQuery = q != null && !q.isBlank();

        // 카테고리는 자기 + 모든 하위 카테고리다(product.api#102). 상품은 소분류에 달리는데 헤더 메뉴는
        // 대분류도 ?category= 로 링크하므로, 정확히 그 카테고리만 보면 대분류 화면이 비고 승인된 상품이
        // 카테고리 메뉴에서 보이지 않는다.
        if (hasCategory && hasQuery) {
            products = productRepository.findByCategoryIdInAndNameContainingIgnoreCase(selfAndDescendants(categoryId), q);
        } else if (hasCategory) {
            products = productRepository.findByCategoryIdIn(selfAndDescendants(categoryId));
        } else if (hasQuery) {
            products = productRepository.findByNameContainingIgnoreCase(q);
        } else {
            products = productRepository.findAll();
        }

        return toSummaries(includeHidden ? products : onlyLive(products));
    }

    /** 카테고리 id 와 그 모든 하위 카테고리 id(깊이 제한 없음). 카테고리는 수십 개라 한 번에 읽어 메모리에서 푼다. */
    private Set<Long> selfAndDescendants(Long categoryId) {
        Map<Long, List<Long>> childrenByParent = categoryRepository.findAll().stream()
                .filter(c -> c.getParent() != null)
                .collect(Collectors.groupingBy(c -> c.getParent().getId(),
                        Collectors.mapping(Category::getId, Collectors.toList())));
        Set<Long> ids = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>(List.of(categoryId));
        while (!queue.isEmpty()) {
            Long id = queue.poll();
            if (ids.add(id)) {
                queue.addAll(childrenByParent.getOrDefault(id, List.of()));
            }
        }
        return ids;
    }

    /**
     * 상품 판매자의 공개 정보(product.front#36). 노출 여부(LIVE) 판정은 호출부가 먼저 한다.
     * 판매자는 LAZY 라 이 트랜잭션 안에서 읽는다(open-in-view 꺼짐).
     */
    public PublicSellerInfo publicSellerInfoOf(Long productId) {
        Seller s = productRepository.findById(productId)
                .orElseThrow(() -> new NoSuchElementException("product not found: " + productId))
                .getSeller();
        return new PublicSellerInfo(s.getName(), s.getRepresentativeName(), s.getBusinessRegistrationNo(),
                s.getMailOrderSalesNo(), s.getAddress(), s.getPhone(), s.getEmail(), s.getCsContact());
    }

    /**
     * 관리자 상품 목록(admin.front#50) - 상태와 판매자까지. 최신 등록 순.
     *
     * @param status null 이면 전 상태. 호출부(ProductController)가 staff 역할을 확인한 뒤에만 부른다.
     */
    public List<ProductManagementSummary> listForManagement(ProductStatus status) {
        List<Product> products = status != null
                ? productRepository.findByStatusOrderByIdDesc(status)
                : productRepository.findAllByOrderByIdDesc();
        // toSummaries 는 입력 순서를 그대로 유지한다 - 같은 인덱스끼리 짝지어 판매자·상태를 붙인다.
        List<ProductSummaryResponse> summaries = toSummaries(products);
        List<ProductManagementSummary> result = new ArrayList<>(summaries.size());
        for (int i = 0; i < summaries.size(); i++) {
            ProductSummaryResponse s = summaries.get(i);
            Product p = products.get(i);
            result.add(new ProductManagementSummary(
                    s.id(), s.categoryId(), s.name(), s.price(), s.stockQuantity(), s.thumbnailUrl(),
                    p.getStatus().name(), p.getSeller().getId(), p.getSeller().getName()));
        }
        return result;
    }

    /**
     * RAG 검색(product.api#46)처럼 특정 id 목록을 관련도 순서 그대로 요약 조회할 때 쓴다.
     * {@code findAllById}는 순서를 보장하지 않으므로 입력 순서를 기준으로 재정렬한다.
     */
    public List<ProductSummaryResponse> getSummariesByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Product> byId = productRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
        // RAG 답변의 근거로 쓰인다 - 숨김 상품이 섞이면 공개 전 상품이 답변으로 새어 나간다.
        List<Product> orderedProducts = ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .filter(Product::isPubliclyVisible)
                .toList();
        return toSummaries(orderedProducts);
    }

    /** 판매자 한 곳의 상품 전부(숨김 포함). 파트너 포털 전용 - 호출부가 sellerId 를 토큰에서 얻었어야 한다. */
    public List<ProductSummaryResponse> listSellerProducts(Long sellerId) {
        return toSummaries(productRepository.findBySellerIdOrderByIdDesc(sellerId));
    }

    private static List<Product> onlyLive(List<Product> products) {
        return products.stream().filter(Product::isPubliclyVisible).toList();
    }

    private List<ProductSummaryResponse> toSummaries(List<Product> products) {
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
        // 대표 오퍼도 variant·재고처럼 목록 전체에 대해 한 번만 조회한다 - 상품마다 조회하면
        // 상품 수만큼 쿼리가 나간다(product.api#72).
        Map<Long, Offer> featuredByVariant = offerService.featuredOffersOfActive(variantsByProduct.values());

        return products.stream()
                .map(p -> {
                    List<ProductVariant> variants = variantsByProduct.getOrDefault(p.getId(), List.of());
                    return new ProductSummaryResponse(
                            p.getId(),
                            p.getCategory().getId(),
                            p.getName(),
                            offerService.representativePrice(variants, featuredByVariant),
                            totalStock(variants, stockByVariant),
                            p.getImages().isEmpty() ? null : p.getImages().get(0).getImageUrl(),
                            p.getListPrice(),
                            p.getRatingAvg(),
                            p.getReviewCount(),
                            p.getShippingBadge(),
                            p.isFreeShipping(),
                            p.getBrand());
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
    @CacheEvict(cacheNames = { CacheNames.MAIN_BEST, CacheNames.MAIN_NEW, CacheNames.MAIN_BY_CATEGORY }, allEntries = true)
    public ProductResponse createProduct(ProductCreateRequest request) {
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new NoSuchElementException("category not found: " + request.categoryId()));

        Product product = new Product();
        product.setCategory(category);
        product.setName(request.name());
        product.setDescription(request.description());
        applyDisplayAttributes(product, request.listPrice(), request.ratingAvg(), request.reviewCount(),
                request.shippingBadge(), request.freeShipping(), request.brand());
        // 판매자/노출상태는 생략 가능하다(product.api#29). admin.front 의 상품 등록 폼은 아직 이 두
        // 값을 보내지 않으므로, null 이면 지금까지의 동작(자사 판매 / 즉시 노출)을 그대로 유지한다 -
        // 필수로 만들면 폼을 고치기 전까지 상품 등록이 전부 깨진다.
        product.setSeller(resolveSeller(request.sellerId()));
        product.setStatus(request.status() != null ? ProductStatus.valueOf(request.status()) : ProductStatus.LIVE);
        addImages(product, request.imageUrls());

        Product saved = productRepository.save(product);

        ProductVariant defaultVariant = new ProductVariant(saved, null, request.price());
        productVariantRepository.save(defaultVariant);
        inventoryService.initialize(defaultVariant, request.stockQuantity());
        offerService.createFirstPartyOffer(saved, defaultVariant);

        return toResponse(saved);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = PRODUCT_CACHE, key = "#id"),
            @CacheEvict(cacheNames = { CacheNames.MAIN_BEST, CacheNames.MAIN_NEW, CacheNames.MAIN_BY_CATEGORY },
                allEntries = true)
    })
    public ProductResponse updateProduct(Long id, ProductUpdateRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("product not found: " + id));
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new NoSuchElementException("category not found: " + request.categoryId()));

        product.setCategory(category);
        product.setName(request.name());
        product.setDescription(request.description());
        applyDisplayAttributes(product, request.listPrice(), request.ratingAvg(), request.reviewCount(),
                request.shippingBadge(), freeShippingFor(id, request.freeShipping()), request.brand());
        // null 은 "안 보냈다"이지 "비우라"가 아니다 - 기존 값을 유지한다(product.api#47 과 같은 이유로,
        // 폼이 채우지 않은 필드가 조용히 초기화되면 안 된다).
        if (request.sellerId() != null) {
            product.setSeller(resolveSeller(request.sellerId()));
        }
        if (request.status() != null) {
            product.setStatus(ProductStatus.valueOf(request.status()));
        }

        product.getImages().clear();
        addImages(product, request.imageUrls());

        // request.price()/stockQuantity()는 ProductForm이 GET 응답의 대표값(활성 variant 중
        // 최저가 / 전 variant 재고 합계)을 그대로 채워 보낸 값이다. SKU가 1개뿐일 때는 그
        // 대표값이 곧 SKU 값과 같아 안전하지만, SKU가 2개 이상이면 이 요청으로 어느 SKU를
        // 덮어써야 하는지 알 수 없다 - 과거에는 id가 가장 작은 variant를 임의로 덮어써서
        // 재고가 부풀고 가격이 뒤바뀌었다(product.api#47). SKU별 가격/재고는 반드시
        // VariantManager의 PUT /variants/{variantId} 경로로만 바꾼다.
        List<ProductVariant> activeVariants = productVariantRepository.findByProductId(id).stream()
                .filter(ProductVariant::isActive)
                .toList();
        if (activeVariants.size() == 1) {
            ProductVariant only = activeVariants.get(0);
            only.setPrice(request.price());
            inventoryService.adjustTo(only, request.stockQuantity());
            offerService.syncSellerOfferPrice(product, only);
        }

        return toResponse(product);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = PRODUCT_CACHE, key = "#id"),
            @CacheEvict(cacheNames = { CacheNames.MAIN_BEST, CacheNames.MAIN_NEW, CacheNames.MAIN_BY_CATEGORY },
                allEntries = true)
    })
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
    @Caching(evict = {
            @CacheEvict(cacheNames = PRODUCT_CACHE, key = "#productId"),
            @CacheEvict(cacheNames = { CacheNames.MAIN_BEST, CacheNames.MAIN_NEW, CacheNames.MAIN_BY_CATEGORY },
                allEntries = true)
    })
    public VariantResponse createVariant(Long productId, CreateVariantRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new NoSuchElementException("product not found: " + productId));

        ProductVariant variant = new ProductVariant(product, request.sku(), request.price());
        if (request.optionValueIds() != null && !request.optionValueIds().isEmpty()) {
            variant.getOptionValues().addAll(productOptionValueRepository.findAllById(request.optionValueIds()));
            // createProduct는 옵션 없는 기본 variant를 항상 하나 만든다. 이후 옵션 기반 SKU를
            // 처음 추가하는 시점에는 그 기본 variant가 더 이상 팔 수 있는 조합이 아니다 -
            // 매장에서는 안 보이는데(product.front는 옵션 매칭 variant만 노출) 대표가/총재고
            // 계산에는 계속 잡혀서 재고가 부풀어 보였다(product.api#47). 비활성화해서 제외한다.
            deactivateOptionlessVariants(productId);
        }
        productVariantRepository.save(variant);
        inventoryService.initialize(variant, request.stockQuantity());
        offerService.createFirstPartyOffer(product, variant);

        return toVariantResponse(variant, request.stockQuantity());
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = PRODUCT_CACHE, key = "#productId"),
            @CacheEvict(cacheNames = { CacheNames.MAIN_BEST, CacheNames.MAIN_NEW, CacheNames.MAIN_BY_CATEGORY },
                allEntries = true)
    })
    public VariantResponse updateVariant(Long productId, Long variantId, UpdateVariantRequest request) {
        ProductVariant variant = findVariantOrThrow(productId, variantId);
        variant.setSku(request.sku());
        variant.setPrice(request.price());
        variant.setActive(request.active());
        inventoryService.adjustTo(variant, request.stockQuantity());
        // 쇼핑몰 대표가는 오퍼 가격에서 나온다 - SKU 만 바꾸면 화면 가격이 그대로다(product.api#77).
        offerService.syncSellerOfferPrice(variant.getProduct(), variant);
        return toVariantResponse(variant, request.stockQuantity());
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = PRODUCT_CACHE, key = "#productId"),
            @CacheEvict(cacheNames = { CacheNames.MAIN_BEST, CacheNames.MAIN_NEW, CacheNames.MAIN_BY_CATEGORY },
                allEntries = true)
    })
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
        List<ProductVariant> variants = productVariantRepository.findAllByIdWithProduct(variantIds);
        List<Long> productIds = variants.stream().map(v -> v.getProduct().getId()).distinct().toList();
        Map<Long, ProductPolicy> policies = purchaseRules.policiesOf(productIds);
        Set<Long> suspended = purchaseRules.saleSuspendedOf(productIds);
        return variants.stream()
                .map(v -> {
                    ProductPolicy policy = policies.get(v.getProduct().getId());
                    return new VariantResolveResponse(
                            v.getId(),
                            v.getProduct().getId(),
                            v.getProduct().getName(),
                            v.getPrice(),
                            // order.api 는 active=false 를 주문 불가로 거부한다. 숨김 상품(product.api#74)과
                            // 판매 기간 밖(product.api#97)·판매자 정지·해지(product.api#100)도 같은 이유로 주문되면
                            // 안 된다. 판정은 PurchaseRules 한 곳에 있다 - offers/resolve 가 같은 식을 써야 하기
                            // 때문이다(product.api#108).
                            purchaseRules.purchasable(v, policy, suspended),
                            purchaseRules.maxPurchaseQuantity(policy));
                })
                .toList();
    }


    /**
     * 판매 정책에 배송비 정책이 있으면 무료배송 표시는 거기서 파생한다(product.api#79, admin.front#56) —
     * 기본 정보 폼의 값으로 덮으면 "정책은 무료, 배지는 유료"처럼 둘이 어긋난다. 관리자·파트너 수정이
     * 모두 이 경로를 지난다. 정책이 없거나 배송비 정책이 비어 있을 때만 요청 값을 쓴다.
     */
    private boolean freeShippingFor(Long productId, boolean requested) {
        ProductPolicy policy = purchaseRules.policiesOf(List.of(productId)).get(productId);
        if (policy == null || policy.getShippingFeeType() == null) {
            return requested;
        }
        return policy.getShippingFeeType() == ShippingFeeType.FREE;
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

    private void deactivateOptionlessVariants(Long productId) {
        productVariantRepository.findByProductId(productId).stream()
                .filter(ProductVariant::isActive)
                .filter(v -> v.getOptionValues().isEmpty())
                .forEach(v -> v.setActive(false));
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

    // 대표가는 이제 variant.price 가 아니라 대표 오퍼의 가격이다(product.api#31).
    // V17 백필과 createFirstPartyOffer 로 모든 variant 에 오퍼가 있으므로 값은 전환 전과 같다.
    private BigDecimal representativePrice(List<ProductVariant> variants) {
        return offerService.representativePrice(variants);
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
                product.getUpdatedAt(),
                product.getListPrice(),
                product.getRatingAvg(),
                product.getReviewCount(),
                product.getShippingBadge(),
                product.isFreeShipping(),
                product.getBrand(),
                product.getSeller().getId(),
                product.getSeller().getName(),
                product.getStatus().name());
    }

    /** sellerId 가 null 이면 자사 판매자로 본다. 존재하지 않는 id 면 조용히 넘기지 않고 실패시킨다. */
    private Seller resolveSeller(Long sellerId) {
        Long id = sellerId != null ? sellerId : FIRST_PARTY_SELLER_ID;
        return sellerRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("seller not found: " + id));
    }

    private void applyDisplayAttributes(Product product, BigDecimal listPrice, BigDecimal ratingAvg,
            Integer reviewCount, String shippingBadge, boolean freeShipping, String brand) {
        product.setListPrice(listPrice);
        product.setRatingAvg(ratingAvg);
        product.setReviewCount(reviewCount != null ? reviewCount : 0);
        product.setShippingBadge(shippingBadge);
        product.setFreeShipping(freeShipping);
        product.setBrand(brand);
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
        return new CategoryResponse(category.getId(), category.getName(), parentId, category.getSortOrder());
    }
}