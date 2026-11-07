package com.dh.product.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dh.product.dto.CartDtos.CartItemAddRequest;
import com.dh.product.dto.CartDtos.CartItemUpdateRequest;
import com.dh.product.dto.CartDtos.CartResponse;
import com.dh.product.service.CartService;

import jakarta.validation.Valid;

/**
 * 장바구니 API(product.api#143).
 *
 * <ul>
 *   <li>비로그인: CART_ID 쿠키(UUID)로 식별. 쿠키가 없으면 새로 발급한다 - 아이템 없는 빈 카트라 무해하다.</li>
 *   <li>로그인: 게이트웨이가 주입한 {@code X-User-Id}(Keycloak sub)로 식별해 기기 간에 공유된다.
 *       product.posselect.com 은 gateway {@code optional-auth-hosts} 라 쿠키가 유효할 때만 이 헤더가 실리고,
 *       클라이언트가 보낸 같은 이름의 헤더는 게이트웨이가 지운다.</li>
 *   <li>로그인 요청에 CART_ID 쿠키가 함께 오면 그 비로그인 장바구니를 계정 장바구니로 합치고 쿠키를
 *       만료시킨다. 로그인은 customer 호스트에서 일어나 이 쿠키(product 호스트 전용)를 볼 수 없으므로
 *       로그인 이벤트가 아니라 로그인 후 첫 장바구니 요청에서 합친다. 쿠키를 지웠으니 로그아웃하면
 *       빈 장바구니에서 시작한다.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private static final String CART_COOKIE = "CART_ID";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final int CART_COOKIE_MAX_AGE_SECONDS = 60 * 60 * 24 * 30;
    private static final CartResponse EMPTY_CART = new CartResponse(List.of(), BigDecimal.ZERO, BigDecimal.ZERO);

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public ResponseEntity<CartResponse> getCart(
            @RequestHeader(name = USER_ID_HEADER, required = false) String userId,
            @CookieValue(name = CART_COOKIE, required = false) String cartId) {
        CartRef ref = resolve(userId, cartId);
        CartResponse body = ref.fresh() ? EMPTY_CART : cartService.getCart(ref.id());
        return respond(ref, body);
    }

    @PostMapping("/items")
    public ResponseEntity<CartResponse> addItem(
            @RequestHeader(name = USER_ID_HEADER, required = false) String userId,
            @CookieValue(name = CART_COOKIE, required = false) String cartId,
            @Valid @RequestBody CartItemAddRequest request) {
        CartRef ref = resolve(userId, cartId);
        return respond(ref, cartService.addItem(ref.id(), request.variantId(), request.quantity()));
    }

    @PutMapping("/items/{variantId}")
    public ResponseEntity<CartResponse> updateItem(
            @RequestHeader(name = USER_ID_HEADER, required = false) String userId,
            @CookieValue(name = CART_COOKIE, required = false) String cartId,
            @PathVariable Long variantId,
            @Valid @RequestBody CartItemUpdateRequest request) {
        CartRef ref = resolve(userId, cartId);
        CartResponse body = ref.fresh() ? EMPTY_CART
                : cartService.updateItem(ref.id(), variantId, request.quantity());
        return respond(ref, body);
    }

    @DeleteMapping("/items/{variantId}")
    public ResponseEntity<CartResponse> removeItem(
            @RequestHeader(name = USER_ID_HEADER, required = false) String userId,
            @CookieValue(name = CART_COOKIE, required = false) String cartId,
            @PathVariable Long variantId) {
        CartRef ref = resolve(userId, cartId);
        CartResponse body = ref.fresh() ? EMPTY_CART : cartService.removeItem(ref.id(), variantId);
        return respond(ref, body);
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(
            @RequestHeader(name = USER_ID_HEADER, required = false) String userId,
            @CookieValue(name = CART_COOKIE, required = false) String cartId) {
        if (isLoggedIn(userId)) {
            String userCartId = CartService.userCartId(userId);
            cartService.clear(userCartId);
            if (cartId != null) {
                // 남아 있던 비로그인 장바구니도 비운다 - 화면에 보인 장바구니(병합 후)를 주문한 것이다.
                if (CartService.isGuestCartId(cartId)) {
                    cartService.clear(cartId);
                }
                return ResponseEntity.noContent().header("Set-Cookie", expiredCartCookie().toString()).build();
            }
        } else if (CartService.isGuestCartId(cartId)) {
            cartService.clear(cartId);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 이 요청이 쓸 장바구니를 정한다.
     *
     * @param id 장바구니 id(CartService 기준)
     * @param fresh 방금 만든 비로그인 id 라 비어 있음이 확실한 경우
     * @param cookie 응답에 실을 Set-Cookie(발급 또는 만료), 없으면 null
     * @param merged 이번 요청에서 계정 장바구니로 옮긴 품목 수
     */
    private record CartRef(String id, boolean fresh, ResponseCookie cookie, int merged) {
    }

    private CartRef resolve(String userId, String cookieCartId) {
        if (isLoggedIn(userId)) {
            String userCartId = CartService.userCartId(userId);
            if (cookieCartId == null) {
                return new CartRef(userCartId, false, null, 0);
            }
            int merged = CartService.isGuestCartId(cookieCartId)
                    ? cartService.mergeGuestCart(cookieCartId, userCartId)
                    : 0;
            return new CartRef(userCartId, false, expiredCartCookie(), merged);
        }
        if (CartService.isGuestCartId(cookieCartId)) {
            return new CartRef(cookieCartId, false, null, 0);
        }
        // 쿠키가 없거나 우리가 발급한 형식이 아니면 새로 발급한다(위조된 값은 덮어쓴다).
        String id = UUID.randomUUID().toString();
        return new CartRef(id, true, cartCookie(id, CART_COOKIE_MAX_AGE_SECONDS), 0);
    }

    private static boolean isLoggedIn(String userId) {
        return userId != null && !userId.isBlank();
    }

    private ResponseEntity<CartResponse> respond(CartRef ref, CartResponse body) {
        CartResponse withMerge = ref.merged() > 0 ? body.withMergedItemCount(ref.merged()) : body;
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (ref.cookie() != null) {
            builder.header("Set-Cookie", ref.cookie().toString());
        }
        return builder.body(withMerge);
    }

    private static ResponseCookie expiredCartCookie() {
        return cartCookie("", 0);
    }

    private static ResponseCookie cartCookie(String value, int maxAgeSeconds) {
        return ResponseCookie.from(CART_COOKIE, value)
                .httpOnly(true)
                .path("/")
                .maxAge(maxAgeSeconds)
                .sameSite("Lax")
                .build();
    }
}
