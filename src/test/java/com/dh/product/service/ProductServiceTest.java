package com.dh.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dh.product.domain.Category;
import com.dh.product.domain.Inventory;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductOption;
import com.dh.product.domain.ProductOptionValue;
import com.dh.product.domain.ProductStatus;
import com.dh.product.domain.ProductVariant;
import com.dh.product.domain.Seller;
import com.dh.product.dto.ProductDtos.CreateVariantRequest;
import com.dh.product.dto.ProductDtos.ProductResponse;
import com.dh.product.dto.ProductDtos.ProductSummaryResponse;
import com.dh.product.dto.ProductDtos.ProductUpdateRequest;
import com.dh.product.dto.ProductDtos.VariantResponse;
import com.dh.product.repository.CategoryRepository;
import com.dh.product.repository.InventoryRepository;
import com.dh.product.repository.ProductOptionRepository;
import com.dh.product.repository.ProductOptionValueRepository;
import com.dh.product.repository.ProductRepository;
import com.dh.product.repository.OfferRepository;
import com.dh.product.repository.ProductVariantRepository;
import com.dh.product.service.offer.LowestPriceFeaturedOfferSelector;
import com.dh.product.service.offer.OfferService;
import com.dh.product.repository.SellerRepository;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private ProductVariantRepository productVariantRepository;
    @Mock
    private ProductOptionRepository productOptionRepository;
    @Mock
    private ProductOptionValueRepository productOptionValueRepository;
    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private SellerRepository sellerRepository;
    @Mock
    private OfferRepository offerRepository;

    private ProductService productService;

    /**
     * 대표가 계산이 오퍼로 옮겨졌다(product.api#31). {@code OfferService} 를 목으로 두면
     * 가격이 null 로 돌아와 이 클래스의 검증 대상("활성 variant 중 최저가")이 사라지므로,
     * 목 리포지토리 위에 실제 {@code OfferService} 를 올린다.
     *
     * <p>{@code offerRepository} 가 빈 목록을 돌려주므로 대표가는 폴백 경로(variant.price)를
     * 탄다 - 즉 이 테스트들은 <b>오퍼가 아직 없을 때도 기존 규칙이 그대로 성립하는지</b>를 본다.
     * 오퍼가 있을 때의 동작은 {@code OfferIntegrationTest} 가 실제 DB 로 검증한다.
     */
    @BeforeEach
    void setUp() {
        OfferService offerService = new OfferService(offerRepository, new LowestPriceFeaturedOfferSelector());
        productService = new ProductService(
                productRepository, categoryRepository, productVariantRepository,
                productOptionRepository, productOptionValueRepository, inventoryRepository,
                inventoryService, sellerRepository, offerService);
    }

    @Test
    void listProducts_ShouldCalculateLowestPriceFromActiveVariantsOnly() {
        // given
        Category cat = new Category();
        org.springframework.test.util.ReflectionTestUtils.setField(cat, "id", 10L);

        Product product = new Product();
        product.setName("Test Product");
        product.setDescription("Desc");
        product.setCategory(cat);
        org.springframework.test.util.ReflectionTestUtils.setField(product, "id", 1L);
        attachFirstPartySeller(product);

        // variant1 is active, price 500
        ProductVariant variant1 = new ProductVariant(product, "v1", BigDecimal.valueOf(500));
        org.springframework.test.util.ReflectionTestUtils.setField(variant1, "id", 101L);

        // variant2 is inactive, price 100 (should be ignored)
        ProductVariant variant2 = new ProductVariant(product, "v2", BigDecimal.valueOf(100));
        variant2.setActive(false);
        org.springframework.test.util.ReflectionTestUtils.setField(variant2, "id", 102L);

        // variant3 is active, price 300 (lowest active)
        ProductVariant variant3 = new ProductVariant(product, "v3", BigDecimal.valueOf(300));
        org.springframework.test.util.ReflectionTestUtils.setField(variant3, "id", 103L);

        given(productRepository.findAll()).willReturn(List.of(product));
        given(productVariantRepository.findByProductIdIn(List.of(1L)))
                .willReturn(List.of(variant1, variant2, variant3));

        Inventory inv1 = new Inventory(variant1, 10);
        Inventory inv3 = new Inventory(variant3, 5);
        given(inventoryRepository.findByVariantIdIn(anyList())).willReturn(List.of(inv1, inv3));

        // when
        List<ProductSummaryResponse> result = productService.listProducts(null, null);

        // then
        assertThat(result).hasSize(1);
        ProductSummaryResponse summary = result.get(0);
        assertThat(summary.price()).isEqualByComparingTo(BigDecimal.valueOf(300));
        assertThat(summary.stockQuantity()).isEqualTo(15); // 10 + 5, variant2 has 0 but is inactive anyway
    }

    @Test
    void getProduct_ShouldReturnRepresentativePriceFromActiveVariantsOnly() {
        // given
        Category cat = new Category();
        org.springframework.test.util.ReflectionTestUtils.setField(cat, "id", 10L);

        Product product = new Product();
        product.setName("Test Product");
        product.setDescription("Desc");
        product.setCategory(cat);
        org.springframework.test.util.ReflectionTestUtils.setField(product, "id", 1L);
        attachFirstPartySeller(product);

        ProductVariant variant1 = new ProductVariant(product, "v1", BigDecimal.valueOf(1000));
        org.springframework.test.util.ReflectionTestUtils.setField(variant1, "id", 101L);

        ProductVariant variant2 = new ProductVariant(product, "v2", BigDecimal.valueOf(200));
        variant2.setActive(false);
        org.springframework.test.util.ReflectionTestUtils.setField(variant2, "id", 102L);

        given(productRepository.findById(1L)).willReturn(Optional.of(product));
        given(productVariantRepository.findByProductId(1L)).willReturn(List.of(variant1, variant2));

        Inventory inv1 = new Inventory(variant1, 10);
        given(inventoryRepository.findByVariantIdIn(anyList())).willReturn(List.of(inv1));

        // when
        ProductResponse response = productService.getProduct(1L);

        // then
        assertThat(response.price()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        assertThat(response.stockQuantity()).isEqualTo(10);
    }

    @Test
    void updateProduct_WithMultipleActiveVariants_ShouldNotOverwritePriceOrStock() {
        // given: 색상 2종 SKU를 가진 상품 - product.api#47 재현 조건
        Category cat = new Category();
        org.springframework.test.util.ReflectionTestUtils.setField(cat, "id", 10L);

        Product product = new Product();
        product.setName("Test Product");
        product.setCategory(cat);
        org.springframework.test.util.ReflectionTestUtils.setField(product, "id", 1L);
        attachFirstPartySeller(product);

        ProductVariant black = new ProductVariant(product, "sku-black", BigDecimal.valueOf(1000));
        org.springframework.test.util.ReflectionTestUtils.setField(black, "id", 101L);
        ProductVariant white = new ProductVariant(product, "sku-white", BigDecimal.valueOf(1200));
        org.springframework.test.util.ReflectionTestUtils.setField(white, "id", 102L);

        given(productRepository.findById(1L)).willReturn(Optional.of(product));
        given(categoryRepository.findById(10L)).willReturn(Optional.of(cat));
        given(productVariantRepository.findByProductId(1L)).willReturn(List.of(black, white));
        given(inventoryRepository.findByVariantIdIn(anyList())).willReturn(List.of());

        ProductUpdateRequest request = new ProductUpdateRequest(
                10L, "Test Product", null, BigDecimal.valueOf(9999), 9999, List.of(),
                null, null, null, null, false, null, null, null);

        // when: 편집 화면이 대표값(최저가/합계재고)을 그대로 보낸다
        productService.updateProduct(1L, request);

        // then: 어느 SKU의 가격/재고도 이 요청으로 바뀌지 않는다
        assertThat(black.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        assertThat(white.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(1200));
        verify(inventoryService, never()).adjustTo(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void updateProduct_WithSingleActiveVariant_ShouldUpdatePriceAndStock() {
        // given
        Category cat = new Category();
        org.springframework.test.util.ReflectionTestUtils.setField(cat, "id", 10L);

        Product product = new Product();
        product.setName("Test Product");
        product.setCategory(cat);
        org.springframework.test.util.ReflectionTestUtils.setField(product, "id", 1L);
        attachFirstPartySeller(product);

        ProductVariant only = new ProductVariant(product, null, BigDecimal.valueOf(1000));
        org.springframework.test.util.ReflectionTestUtils.setField(only, "id", 101L);

        given(productRepository.findById(1L)).willReturn(Optional.of(product));
        given(categoryRepository.findById(10L)).willReturn(Optional.of(cat));
        given(productVariantRepository.findByProductId(1L)).willReturn(List.of(only));
        given(inventoryRepository.findByVariantIdIn(anyList())).willReturn(List.of());

        ProductUpdateRequest request = new ProductUpdateRequest(
                10L, "Test Product", null, BigDecimal.valueOf(1500), 20, List.of(),
                null, null, null, null, false, null, null, null);

        // when
        productService.updateProduct(1L, request);

        // then: SKU가 1개뿐이면 대표값 = SKU 값이라 그대로 반영해도 안전하다
        assertThat(only.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(1500));
        verify(inventoryService).adjustTo(only, 20);
    }

    @Test
    void createVariant_WithOptionValues_ShouldDeactivateOptionlessDefaultVariant() {
        // given: createProduct가 만든 옵션 없는 기본 variant가 남아 있는 상태
        Category cat = new Category();
        org.springframework.test.util.ReflectionTestUtils.setField(cat, "id", 10L);

        Product product = new Product();
        product.setName("Test Product");
        product.setCategory(cat);
        org.springframework.test.util.ReflectionTestUtils.setField(product, "id", 1L);
        attachFirstPartySeller(product);

        ProductVariant defaultVariant = new ProductVariant(product, null, BigDecimal.valueOf(1000));
        org.springframework.test.util.ReflectionTestUtils.setField(defaultVariant, "id", 101L);
        assertThat(defaultVariant.isActive()).isTrue();

        ProductOption color = new ProductOption(product, "색상");
        org.springframework.test.util.ReflectionTestUtils.setField(color, "id", 201L);
        ProductOptionValue black = new ProductOptionValue(color, "블랙");
        org.springframework.test.util.ReflectionTestUtils.setField(black, "id", 301L);

        given(productRepository.findById(1L)).willReturn(Optional.of(product));
        given(productOptionValueRepository.findAllById(List.of(301L))).willReturn(List.of(black));
        // deactivateOptionlessVariants가 다시 조회할 때는 기본 variant만 존재(신규 variant는 아직 미저장)
        given(productVariantRepository.findByProductId(1L)).willReturn(List.of(defaultVariant));

        CreateVariantRequest request = new CreateVariantRequest("sku-black", BigDecimal.valueOf(1000), 5, List.of(301L));

        // when
        VariantResponse response = productService.createVariant(1L, request);

        // then: 새 SKU는 옵션값을 갖고, 옵션 없는 기본 variant는 비활성화된다
        assertThat(response.optionValues()).hasSize(1);
        assertThat(defaultVariant.isActive()).isFalse();
    }

    /**
     * products.seller_id/status 는 V14 부터 NOT NULL 이다(product.api#29) - DB 에 판매자 없는 상품은
     * 존재할 수 없으므로 toResponse 도 null 을 방어하지 않는다. 픽스처를 그 현실에 맞춘다.
     */
    private static void attachFirstPartySeller(Product product) {
        Seller seller = new Seller();
        org.springframework.test.util.ReflectionTestUtils.setField(seller, "id", 1L);
        seller.setName("포스셀렉트");
        product.setSeller(seller);
        product.setStatus(ProductStatus.LIVE);
    }
}
