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
        // 숨김 상품은 고객에게 "없는 상품"이다(product.api#74) - 존재 여부를 구분해 알려 주지 않는다.
        Product product = productRepository.findById(productId)
                .filter(Product::isPubliclyVisible)
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
