package com.dh.product.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.product.domain.Inventory;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByProductId(Long productId);
}
