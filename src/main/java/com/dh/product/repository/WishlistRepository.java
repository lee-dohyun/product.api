package com.dh.product.repository;
import java.util.List;
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
    
    /** 찜 여부 표시용 - 상품을 조인하지 않고 ID 만 읽는다. 최근에 찜한 것부터. */
    @Query("SELECT w.product.id FROM WishlistItem w WHERE w.userId = :userId ORDER BY w.createdAt DESC")
    List<Long> findProductIdsByUserId(@Param("userId") String userId);

    Optional<WishlistItem> findByUserIdAndProductId(String userId, Long productId);
    boolean existsByUserIdAndProductId(String userId, Long productId);
}
