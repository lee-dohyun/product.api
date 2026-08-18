package com.dh.product.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.product.domain.Product;
import com.dh.product.domain.WishlistItem;
import com.dh.product.repository.ProductRepository;
import com.dh.product.repository.WishlistRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WishlistService {

    private final WishlistRepository wishlistRepository;
    private final ProductRepository productRepository;

    @Transactional
    public Long addWishlist(String userId, Long productId) {
        if (wishlistRepository.existsByUserIdAndProductId(userId, productId)) {
            throw new IllegalStateException("Already added to wishlist");
        }
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found"));
                
        WishlistItem item = new WishlistItem(userId, product);
        WishlistItem saved = wishlistRepository.save(item);
        return saved.getId();
    }

    @Transactional(readOnly = true)
    public List<WishlistItem> getWishlists(String userId) {
        return wishlistRepository.findByUserId(userId);
    }

    @Transactional
    public void removeWishlist(String userId, Long productId) {
        wishlistRepository.findByUserIdAndProductId(userId, productId)
                .ifPresent(wishlistRepository::delete);
    }
}
