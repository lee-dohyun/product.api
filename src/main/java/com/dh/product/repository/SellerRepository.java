package com.dh.product.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.product.domain.Seller;
import com.dh.product.domain.SellerStatus;

public interface SellerRepository extends JpaRepository<Seller, Long> {
    List<Seller> findByStatus(SellerStatus status);
}
