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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dh.product.dto.CartDtos.CartItemAddRequest;
import com.dh.product.dto.CartDtos.CartItemUpdateRequest;
import com.dh.product.dto.CartDtos.CartResponse;
import com.dh.product.service.CartService;

import jakarta.validation.Valid;

/**
 * 로그인 여부와 무관하게 CART_ID 쿠키로 장바구니를 식별하는 익명 카트.
 * 쿠키가 없으면 매 요청마다 새로 발급 — 아이템 없는 빈 카트라 무해함.
 */
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private static final String CART_COOKIE = "CART_ID";
    private static final int CART_COOKIE_MAX_AGE_SECONDS = 60 * 60 * 24 * 30;
    private static final CartResponse EMPTY_CART = new CartResponse(List.of(), BigDecimal.ZERO);

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public ResponseEntity<CartResponse> getCart(@CookieValue(name = CART_COOKIE, required = false) String cartId) {
        if (cartId == null) {
            return withCartCookie(newCartId(), EMPTY_CART);
        }
        return ResponseEntity.ok(cartService.getCart(cartId));
    }

    @PostMapping("/items")
    public ResponseEntity<CartResponse> addItem(
            @CookieValue(name = CART_COOKIE, required = false) String cartId,
            @Valid @RequestBody CartItemAddRequest request) {
        String id = cartId != null ? cartId : newCartId();
        CartResponse response = cartService.addItem(id, request.variantId(), request.quantity());
        return cartId != null ? ResponseEntity.ok(response) : withCartCookie(id, response);
    }

    @PutMapping("/items/{variantId}")
    public ResponseEntity<CartResponse> updateItem(
            @CookieValue(name = CART_COOKIE, required = false) String cartId,
            @PathVariable Long variantId,
            @Valid @RequestBody CartItemUpdateRequest request) {
        if (cartId == null) {
            return withCartCookie(newCartId(), EMPTY_CART);
        }
        return ResponseEntity.ok(cartService.updateItem(cartId, variantId, request.quantity()));
    }

    @DeleteMapping("/items/{variantId}")
    public ResponseEntity<CartResponse> removeItem(
            @CookieValue(name = CART_COOKIE, required = false) String cartId,
            @PathVariable Long variantId) {
        if (cartId == null) {
            return withCartCookie(newCartId(), EMPTY_CART);
        }
        return ResponseEntity.ok(cartService.removeItem(cartId, variantId));
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(@CookieValue(name = CART_COOKIE, required = false) String cartId) {
        if (cartId != null) {
            cartService.clear(cartId);
        }
        return ResponseEntity.noContent().build();
    }

    private String newCartId() {
        return UUID.randomUUID().toString();
    }

    private ResponseEntity<CartResponse> withCartCookie(String cartId, CartResponse body) {
        ResponseCookie cookie = ResponseCookie.from(CART_COOKIE, cartId)
                .httpOnly(true)
                .path("/")
                .maxAge(CART_COOKIE_MAX_AGE_SECONDS)
                .sameSite("Lax")
                .build();
        return ResponseEntity.ok().header("Set-Cookie", cookie.toString()).body(body);
    }
}
