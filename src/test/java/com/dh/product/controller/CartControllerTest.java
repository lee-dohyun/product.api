package com.dh.product.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.cache.test.autoconfigure.AutoConfigureCache;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dh.product.dto.CartDtos.CartResponse;
import com.dh.product.service.CartService;

/** 장바구니 식별(쿠키 vs 계정)과 로그인 시 병합 트리거(product.api#143). 병합 자체는 CartMergeRedisIntegrationTest. */
@AutoConfigureCache
@WebMvcTest(CartController.class)
class CartControllerTest {

    private static final String GUEST = "3f2b8c1e-9d4a-4f6b-8a2e-1c5d7e9f0a1b";
    private static final String SUB = "kc-sub-1";
    private static final CartResponse EMPTY = new CartResponse(List.of(), BigDecimal.ZERO, BigDecimal.ZERO);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CartService cartService;

    @MockitoBean
    private com.dh.product.config.AdminJwtVerifier adminJwtVerifier;

    @MockitoBean
    private com.dh.product.config.PartnerJwtVerifier partnerJwtVerifier;

    @MockitoBean
    private com.dh.product.repository.SellerRepository sellerRepository;

    @Test
    void guestWithCookieUsesCookieCartAndSetsNoCookie() throws Exception {
        given(cartService.getCart(GUEST)).willReturn(EMPTY);

        mockMvc.perform(get("/api/cart").cookie(new Cookie("CART_ID", GUEST)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.mergedItemCount").value(0));
        verify(cartService, never()).mergeGuestCart(anyString(), anyString());
    }

    @Test
    void loggedInWithoutCookieUsesAccountCart() throws Exception {
        given(cartService.getCart("user:" + SUB)).willReturn(EMPTY);

        mockMvc.perform(get("/api/cart").header("X-User-Id", SUB))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"));
        verify(cartService).getCart("user:" + SUB);
    }

    @Test
    void loggedInWithGuestCookieMergesThenExpiresCookie() throws Exception {
        given(cartService.mergeGuestCart(GUEST, "user:" + SUB)).willReturn(2);
        given(cartService.getCart("user:" + SUB)).willReturn(EMPTY);

        mockMvc.perform(get("/api/cart").header("X-User-Id", SUB).cookie(new Cookie("CART_ID", GUEST)))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", allOf(containsString("CART_ID="), containsString("Max-Age=0"))))
                .andExpect(jsonPath("$.mergedItemCount").value(2));
    }

    @Test
    void addItemWhileLoggedInWritesAccountCartWithoutIssuingCookie() throws Exception {
        given(cartService.addItem("user:" + SUB, 5L, 1)).willReturn(EMPTY);

        mockMvc.perform(post("/api/cart/items").header("X-User-Id", SUB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":5,\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    /** CART_ID=user:{남의 sub} 로 남의 계정 장바구니에 닿으면 안 된다 - UUID 가 아니면 없는 쿠키로 취급한다. */
    @Test
    void forgedCookiePointingAtAccountCartIsIgnored() throws Exception {
        given(cartService.addItem(anyString(), eq(5L), eq(1))).willReturn(EMPTY);

        mockMvc.perform(post("/api/cart/items").cookie(new Cookie("CART_ID", "user:victim-sub"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":5,\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", allOf(containsString("CART_ID="), not(containsString("victim")))));
        verify(cartService, never()).addItem(eq("user:victim-sub"), any(), anyInt());
    }

    @Test
    void forgedCookieIsNotMergedIntoAccount() throws Exception {
        given(cartService.getCart("user:" + SUB)).willReturn(EMPTY);

        mockMvc.perform(get("/api/cart").header("X-User-Id", SUB).cookie(new Cookie("CART_ID", "user:victim-sub")))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
        verify(cartService, never()).mergeGuestCart(anyString(), anyString());
    }

    @Test
    void clearWhileLoggedInClearsAccountAndLeftoverGuestCart() throws Exception {
        mockMvc.perform(delete("/api/cart").header("X-User-Id", SUB).cookie(new Cookie("CART_ID", GUEST)))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
        verify(cartService).clear("user:" + SUB);
        verify(cartService).clear(GUEST);
    }
}
