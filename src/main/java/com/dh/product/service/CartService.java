package com.dh.product.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.product.domain.ProductVariant;
import com.dh.product.dto.CartDtos.CartItemResponse;
import com.dh.product.dto.CartDtos.CartResponse;
import com.dh.product.repository.ProductVariantRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

// 장바구니는 variant(SKU) 단위로 담는다 - 옵션 없는 상품도 "옵션 없는 variant 1개"라 동일한
// 경로를 탄다.
@Service
@Transactional(readOnly = true)
public class CartService {

    private static final String CART_KEY_PREFIX = "cart:";
    private static final Duration CART_TTL = Duration.ofDays(30);

    private final StringRedisTemplate redisTemplate;
    private final ProductVariantRepository productVariantRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CartService(StringRedisTemplate redisTemplate, ProductVariantRepository productVariantRepository) {
        this.redisTemplate = redisTemplate;
        this.productVariantRepository = productVariantRepository;
    }

    public CartResponse getCart(String cartId) {
        return toResponse(readItems(cartId));
    }

    public CartResponse addItem(String cartId, Long variantId, int quantity) {
        if (!productVariantRepository.existsById(variantId)) {
            throw new NoSuchElementException("variant not found: " + variantId);
        }
        Map<Long, Integer> items = readItems(cartId);
        items.merge(variantId, quantity, Integer::sum);
        writeItems(cartId, items);
        return toResponse(items);
    }

    public CartResponse updateItem(String cartId, Long variantId, int quantity) {
        Map<Long, Integer> items = readItems(cartId);
        if (quantity <= 0) {
            items.remove(variantId);
        } else {
            items.put(variantId, quantity);
        }
        writeItems(cartId, items);
        return toResponse(items);
    }

    public CartResponse removeItem(String cartId, Long variantId) {
        Map<Long, Integer> items = readItems(cartId);
        items.remove(variantId);
        writeItems(cartId, items);
        return toResponse(items);
    }

    public void clear(String cartId) {
        redisTemplate.delete(CART_KEY_PREFIX + cartId);
    }

    private Map<Long, Integer> readItems(String cartId) {
        String json = redisTemplate.opsForValue().get(CART_KEY_PREFIX + cartId);
        if (json == null) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<Long, Integer>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private void writeItems(String cartId, Map<Long, Integer> items) {
        try {
            String json = objectMapper.writeValueAsString(items);
            redisTemplate.opsForValue().set(CART_KEY_PREFIX + cartId, json, CART_TTL);
        } catch (Exception e) {
            throw new IllegalStateException("장바구니 저장 실패", e);
        }
    }

    private CartResponse toResponse(Map<Long, Integer> items) {
        if (items.isEmpty()) {
            return new CartResponse(List.of(), BigDecimal.ZERO);
        }

        Map<Long, ProductVariant> variants = productVariantRepository.findAllById(items.keySet()).stream()
                .collect(Collectors.toMap(ProductVariant::getId, v -> v));

        BigDecimal total = BigDecimal.ZERO;
        List<CartItemResponse> responses = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : items.entrySet()) {
            ProductVariant variant = variants.get(entry.getKey());
            if (variant == null) {
                continue; // variant가 삭제된 경우 장바구니에서 조용히 제외
            }
            int quantity = entry.getValue();
            var product = variant.getProduct();
            responses.add(new CartItemResponse(
                    variant.getId(),
                    product.getId(),
                    product.getName(),
                    variant.getPrice(),
                    quantity,
                    product.getImages().isEmpty() ? null : product.getImages().get(0).getImageUrl()));
            total = total.add(variant.getPrice().multiply(BigDecimal.valueOf(quantity)));
        }
        return new CartResponse(responses, total);
    }
}
