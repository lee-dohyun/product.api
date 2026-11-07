package com.dh.product.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.product.domain.ProductPolicy;
import com.dh.product.domain.ProductVariant;
import com.dh.product.dto.CartDtos.CartItemResponse;
import com.dh.product.dto.CartDtos.CartResponse;
import com.dh.product.repository.ProductVariantRepository;
import com.dh.product.service.shipping.ShippingFeeCalculator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

// 장바구니는 variant(SKU) 단위로 담는다 - 옵션 없는 상품도 "옵션 없는 variant 1개"라 동일한
// 경로를 탄다.
//
// 장바구니 id 는 두 종류다(product.api#143).
// - 비로그인: CART_ID 쿠키의 UUID -> Redis cart:{uuid}
// - 로그인: Keycloak sub -> Redis cart:user:{sub}
// 쿠키 값은 클라이언트가 고칠 수 있으므로 UUID 형식만 받는다(isGuestCartId). 형식 검사가 없으면
// CART_ID=user:{남의 sub} 로 남의 계정 장바구니를 읽고 쓸 수 있다.
@Service
@Transactional(readOnly = true)
public class CartService {

    private static final String CART_KEY_PREFIX = "cart:";
    private static final String USER_CART_ID_PREFIX = "user:";
    private static final Duration CART_TTL = Duration.ofDays(30);

    private final StringRedisTemplate redisTemplate;
    private final ProductVariantRepository productVariantRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final PurchaseRules purchaseRules;

    public CartService(StringRedisTemplate redisTemplate, ProductVariantRepository productVariantRepository,
            PurchaseRules purchaseRules) {
        this.redisTemplate = redisTemplate;
        this.productVariantRepository = productVariantRepository;
        this.purchaseRules = purchaseRules;
    }

    /** 이 상품의 장바구니 합계(같은 상품의 SKU 전부)로 판매 기간·최대 수량을 판정한다(product.api#97). */
    private void checkPurchaseRules(Long productId, Map<Long, Integer> items) {
        int total = productVariantRepository.findAllById(items.keySet()).stream()
                .filter(v -> v.getProduct().getId().equals(productId))
                .mapToInt(v -> items.getOrDefault(v.getId(), 0))
                .sum();
        purchaseRules.checkCart(productId, total);
    }

    /** 로그인 사용자의 장바구니 id. sub 는 게이트웨이가 검증해 주입한 X-User-Id 다. */
    public static String userCartId(String sub) {
        return USER_CART_ID_PREFIX + sub;
    }

    /** 쿠키에서 온 비로그인 장바구니 id 가 우리가 발급한 형식(UUID)인지. 아니면 없는 쿠키로 취급한다. */
    public static boolean isGuestCartId(String cartId) {
        if (cartId == null || cartId.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(cartId).toString().equalsIgnoreCase(cartId);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 비로그인 장바구니를 계정 장바구니로 합치고, 합쳐진 품목(SKU) 수를 돌려준다(product.api#143).
     *
     * <p>비로그인 장바구니는 GETDEL 로 원자적으로 꺼낸다. 같은 쿠키를 단 요청 두 개가 동시에 와도
     * 꺼내는 데 성공한 한 요청만 합치므로 수량이 두 번 더해지지 않는다. 계정 장바구니 쪽의 읽고-쓰기는
     * 다른 장바구니 쓰기 경로와 같은 수준(동시 쓰기 시 마지막 쓰기가 이긴다)이다.
     *
     * <p>같은 SKU 는 수량을 더한다. 더한 결과가 그 상품의 구매 규칙(판매 기간·1회 최대 수량·판매 정지,
     * product.api#97)을 어기면 그 상품은 계정 장바구니에 이미 있던 수량을 그대로 두고 비로그인 쪽을
     * 버린다(계정에 그 상품이 없으면 비로그인 쪽을 그대로 옮긴다 - 각각은 담을 때 규칙을 통과한 값이다).
     * 삭제됐거나 숨김 처리된 SKU 는 옮기지 않는다.
     */
    public int mergeGuestCart(String guestCartId, String userCartId) {
        String json = redisTemplate.opsForValue().getAndDelete(CART_KEY_PREFIX + guestCartId);
        Map<Long, Integer> guest = parse(json);
        if (guest.isEmpty()) {
            return 0;
        }
        Map<Long, Integer> account = readItems(userCartId);

        Map<Long, List<ProductVariant>> guestByProduct = productVariantRepository.findAllById(guest.keySet())
                .stream()
                .filter(v -> v.getProduct().isPubliclyVisible())
                .collect(Collectors.groupingBy(v -> v.getProduct().getId(), LinkedHashMap::new,
                        Collectors.toList()));
        if (guestByProduct.isEmpty()) {
            return 0;
        }
        // variant -> product. 계정 쪽은 숨김 여부와 상관없이 원래 상품에 묶어 둔다(합계 계산용).
        Map<Long, Long> productOf = new HashMap<>();
        if (!account.isEmpty()) {
            productVariantRepository.findAllById(account.keySet())
                    .forEach(v -> productOf.put(v.getId(), v.getProduct().getId()));
        }
        Set<Long> accountProducts = new HashSet<>(productOf.values());
        guestByProduct.values().forEach(vs -> vs.forEach(v -> productOf.put(v.getId(), v.getProduct().getId())));

        int merged = 0;
        for (Map.Entry<Long, List<ProductVariant>> entry : guestByProduct.entrySet()) {
            Long productId = entry.getKey();
            Map<Long, Integer> candidate = new LinkedHashMap<>(account);
            for (ProductVariant v : entry.getValue()) {
                candidate.merge(v.getId(), guest.get(v.getId()), Integer::sum);
            }
            int productTotal = candidate.entrySet().stream()
                    .filter(e -> productId.equals(productOf.get(e.getKey())))
                    .mapToInt(Map.Entry::getValue)
                    .sum();
            try {
                purchaseRules.checkCart(productId, productTotal);
            } catch (PurchaseRuleViolationException e) {
                if (accountProducts.contains(productId)) {
                    continue;
                }
            }
            account = candidate;
            merged += entry.getValue().size();
        }
        if (merged > 0) {
            writeItems(userCartId, account);
        }
        return merged;
    }

    public CartResponse getCart(String cartId) {
        return toResponse(readItems(cartId));
    }

    public CartResponse addItem(String cartId, Long variantId, int quantity) {
        // 숨김 상품의 SKU 는 없는 SKU 와 똑같이 거부한다(product.api#74) - variant id 는 순번이라 추측할 수 있다.
        ProductVariant variant = productVariantRepository.findById(variantId)
                .filter(v -> v.getProduct().isPubliclyVisible())
                .orElseThrow(() -> new NoSuchElementException("variant not found: " + variantId));
        Map<Long, Integer> items = readItems(cartId);
        items.merge(variantId, quantity, Integer::sum);
        checkPurchaseRules(variant.getProduct().getId(), items);
        writeItems(cartId, items);
        return toResponse(items);
    }

    public CartResponse updateItem(String cartId, Long variantId, int quantity) {
        Map<Long, Integer> items = readItems(cartId);
        if (quantity <= 0) {
            items.remove(variantId);
        } else {
            items.put(variantId, quantity);
            // 수량을 늘릴 때도 판매 기간·최대 수량을 다시 본다(product.api#97). 줄이거나 빼는 건 항상 허용.
            productVariantRepository.findById(variantId)
                    .ifPresent(v -> checkPurchaseRules(v.getProduct().getId(), items));
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
        return parse(redisTemplate.opsForValue().get(CART_KEY_PREFIX + cartId));
    }

    private Map<Long, Integer> parse(String json) {
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
            return new CartResponse(List.of(), BigDecimal.ZERO, BigDecimal.ZERO);
        }

        Map<Long, ProductVariant> variants = productVariantRepository.findAllById(items.keySet()).stream()
                .collect(Collectors.toMap(ProductVariant::getId, v -> v));

        // 배송비 정책은 상품 묶음으로 한 번만 읽는다(product.api#72 와 같은 N+1 방지).
        Map<Long, ProductPolicy> policies = purchaseRules.policiesOf(variants.values().stream()
                .map(v -> v.getProduct().getId())
                .distinct()
                .toList());
        BigDecimal total = BigDecimal.ZERO;
        List<CartItemResponse> responses = new ArrayList<>();
        List<ShippingFeeCalculator.Line> shippingLines = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : items.entrySet()) {
            ProductVariant variant = variants.get(entry.getKey());
            if (variant == null || !variant.getProduct().isPubliclyVisible()) {
                continue; // variant가 삭제됐거나 상품이 판매중지(숨김)된 경우 장바구니에서 조용히 제외
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
            BigDecimal amount = variant.getPrice().multiply(BigDecimal.valueOf(quantity));
            total = total.add(amount);
            shippingLines.add(new ShippingFeeCalculator.Line(
                    product.getSeller().getId(), amount, policies.get(product.getId())));
        }
        return new CartResponse(responses, total, ShippingFeeCalculator.total(shippingLines));
    }
}
