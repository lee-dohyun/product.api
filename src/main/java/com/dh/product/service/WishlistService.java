package com.dh.product.service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    public Page<WishlistItem> getWishlists(String userId, Pageable pageable) {
        return wishlistRepository.findByUserId(userId, pageable);
    }

    @Transactional
    public void removeWishlist(String userId, Long productId) {
        wishlistRepository.findByUserIdAndProductId(userId, productId)
                .ifPresent(wishlistRepository::delete);
    }
}
