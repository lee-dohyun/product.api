package com.dh.product.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.product.domain.InventoryTransaction;

public interface InventoryTransactionRepository extends JpaRepository<InventoryTransaction, Long> {

    List<InventoryTransaction> findByInventoryIdOrderByCreatedAtDesc(Long inventoryId);

    void deleteByInventoryId(Long inventoryId);
}
