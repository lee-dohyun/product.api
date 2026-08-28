package com.dh.product.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.product.domain.SellerCategoryPermission;

public interface SellerCategoryPermissionRepository extends JpaRepository<SellerCategoryPermission, Long> {
    boolean existsBySellerIdAndCategoryId(Long sellerId, Long categoryId);

    List<SellerCategoryPermission> findBySellerId(Long sellerId);
}
