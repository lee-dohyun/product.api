package com.dh.product.repository;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.dh.product.domain.WishlistItem;

@Repository
public interface WishlistRepository extends JpaRepository<WishlistItem, Long> {
    
    @Query(value = "SELECT w FROM WishlistItem w JOIN FETCH w.product WHERE w.userId = :userId",
           countQuery = "SELECT count(w) FROM WishlistItem w WHERE w.userId = :userId")
    Page<WishlistItem> findByUserId(@Param("userId") String userId, Pageable pageable);
    
    Optional<WishlistItem> findByUserIdAndProductId(String userId, Long productId);
    boolean existsByUserIdAndProductId(String userId, Long productId);
}
