package com.dh.product.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.dh.product.domain.Product;
import com.dh.product.domain.WishlistItem;
import com.dh.product.service.WishlistService;

@WebMvcTest(WishlistController.class)
class WishlistControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WishlistService wishlistService;
    
    @MockitoBean
    private com.dh.product.config.AdminJwtVerifier adminJwtVerifier;

    @Test
    @DisplayName("찜 추가 API")
    void addWishlist() throws Exception {
        String userId = "user-1";
        Long productId = 100L;
        given(wishlistService.addWishlist(userId, productId)).willReturn(1L);

        mockMvc.perform(post("/api/wishlists")
                .header("X-User-Id", userId)
                .param("productId", productId.toString())
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1L));
    }

    @Test
    @DisplayName("찜 목록 조회 API")
    void getWishlists() throws Exception {
        String userId = "user-1";
        Product product = new Product();
        product.setName("Product A");
        org.springframework.test.util.ReflectionTestUtils.setField(product, "id", 100L);
        WishlistItem item = new WishlistItem(userId, product);
        org.springframework.test.util.ReflectionTestUtils.setField(item, "id", 1L);

        given(wishlistService.getWishlists(userId)).willReturn(List.of(item));

        mockMvc.perform(get("/api/wishlists")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1L))
                .andExpect(jsonPath("$[0].productId").value(100L))
                .andExpect(jsonPath("$[0].productName").value("Product A"));
    }

    @Test
    @DisplayName("찜 취소 API")
    void removeWishlist() throws Exception {
        String userId = "user-1";
        Long productId = 100L;

        mockMvc.perform(delete("/api/wishlists/{productId}", productId)
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
