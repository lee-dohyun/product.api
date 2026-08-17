package com.dh.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dh.product.domain.Category;
import com.dh.product.domain.Inventory;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductVariant;
import com.dh.product.dto.ProductDtos.ProductResponse;
import com.dh.product.dto.ProductDtos.ProductSummaryResponse;
import com.dh.product.repository.CategoryRepository;
import com.dh.product.repository.InventoryRepository;
import com.dh.product.repository.ProductOptionRepository;
import com.dh.product.repository.ProductOptionValueRepository;
import com.dh.product.repository.ProductRepository;
import com.dh.product.repository.ProductVariantRepository;

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

    @InjectMocks
    private ProductService productService;

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
}
