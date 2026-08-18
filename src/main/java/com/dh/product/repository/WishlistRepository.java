package com.dh.product.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.dh.product.domain.WishlistItem;

@Repository
public interface WishlistRepository extends JpaRepository<WishlistItem, Long> {
    List<WishlistItem> findByUserId(String userId);
    Optional<WishlistItem> findByUserIdAndProductId(String userId, Long productId);
    boolean existsByUserIdAndProductId(String userId, Long productId);
}
