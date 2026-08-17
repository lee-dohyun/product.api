package com.dh.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.dh.product.domain.Product;
import com.dh.product.domain.ProductVariant;
import com.dh.product.dto.CartDtos.CartResponse;
import com.dh.product.repository.ProductVariantRepository;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ProductVariantRepository productVariantRepository;

    @InjectMocks
    private CartService cartService;

    @BeforeEach
    void setUp() {
        // leniency for tests that don't need Redis
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void getCart_ReturnsEmptyResponse_WhenCartIsEmpty() {
        given(valueOperations.get("cart:user1")).willReturn(null);

        CartResponse response = cartService.getCart("user1");

        assertThat(response.items()).isEmpty();
        assertThat(response.totalPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void addItem_ThrowsException_WhenVariantNotFound() {
        given(productVariantRepository.existsById(999L)).willReturn(false);

        assertThatThrownBy(() -> cartService.addItem("user1", 999L, 1))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("variant not found");
    }

    @Test
    void addItem_AddsItemAndCalculatesTotal_WhenCartIsInitiallyEmpty() {
        // given
        given(productVariantRepository.existsById(1L)).willReturn(true);
        given(valueOperations.get("cart:user1")).willReturn(null); // empty cart

        Product product = new Product();
        product.setName("Test Product");
        product.setDescription("Desc");

        ProductVariant variant = new ProductVariant(product, "Opt1", BigDecimal.valueOf(100));
        org.springframework.test.util.ReflectionTestUtils.setField(variant, "id", 1L);
        org.springframework.test.util.ReflectionTestUtils.setField(product, "id", 100L);

        given(productVariantRepository.findAllById(Set.of(1L))).willReturn(List.of(variant));

        // when
        CartResponse response = cartService.addItem("user1", 1L, 2);

        // then
        verify(valueOperations).set(eq("cart:user1"), anyString(), any());
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).variantId()).isEqualTo(1L);
        assertThat(response.items().get(0).quantity()).isEqualTo(2);
        assertThat(response.totalPrice()).isEqualByComparingTo(BigDecimal.valueOf(200)); // 100 * 2
    }

    @Test
    void updateItem_RemovesItem_WhenQuantityIsZero() {
        // given
        String existingJson = "{\"1\":2}";
        given(valueOperations.get("cart:user1")).willReturn(existingJson);

        // when
        CartResponse response = cartService.updateItem("user1", 1L, 0);

        // then
        verify(valueOperations).set(eq("cart:user1"), eq("{}"), any());
        assertThat(response.items()).isEmpty();
        assertThat(response.totalPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void removeItem_RemovesVariantFromCart() {
        // given
        String existingJson = "{\"1\":2, \"2\":1}";
        given(valueOperations.get("cart:user1")).willReturn(existingJson);

        Product product = new Product();
        product.setName("Product2");
        product.setDescription("Desc");

        ProductVariant variant2 = new ProductVariant(product, "Opt2", BigDecimal.valueOf(50));
        org.springframework.test.util.ReflectionTestUtils.setField(variant2, "id", 2L);
        org.springframework.test.util.ReflectionTestUtils.setField(product, "id", 101L);

        given(productVariantRepository.findAllById(Set.of(2L))).willReturn(List.of(variant2));

        // when
        CartResponse response = cartService.removeItem("user1", 1L);

        // then
        verify(valueOperations).set(eq("cart:user1"), eq("{\"2\":1}"), any());
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).variantId()).isEqualTo(2L);
        assertThat(response.totalPrice()).isEqualByComparingTo(BigDecimal.valueOf(50));
    }

    @Test
    void clear_DeletesCartKeyFromRedis() {
        cartService.clear("user1");
        verify(redisTemplate).delete("cart:user1");
    }
}
