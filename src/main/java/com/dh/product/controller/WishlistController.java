package com.dh.product.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dh.product.dto.WishlistResponse;
import com.dh.product.service.WishlistService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/wishlists")
@RequiredArgsConstructor
public class WishlistController {

    private final WishlistService wishlistService;

    @PostMapping
    public ResponseEntity<Long> addWishlist(
            @RequestHeader("X-User-Id") String userId,
            @RequestParam Long productId) {
        return ResponseEntity.ok(wishlistService.addWishlist(userId, productId));
    }

    @GetMapping
    public ResponseEntity<List<WishlistResponse>> getWishlists(
            @RequestHeader("X-User-Id") String userId) {
        List<WishlistResponse> responses = wishlistService.getWishlists(userId).stream()
                .map(WishlistResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> removeWishlist(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable Long productId) {
        wishlistService.removeWishlist(userId, productId);
        return ResponseEntity.ok().build();
    }
}
