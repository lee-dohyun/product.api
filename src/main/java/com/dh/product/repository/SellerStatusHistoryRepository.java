package com.dh.product.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.product.domain.SellerStatusHistory;

public interface SellerStatusHistoryRepository extends JpaRepository<SellerStatusHistory, Long> {
    List<SellerStatusHistory> findBySellerIdOrderByIdDesc(Long sellerId);
}
