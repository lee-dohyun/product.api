package com.dh.product.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.product.domain.InventoryTransaction;
import com.dh.product.domain.InventoryTransactionType;

public interface InventoryTransactionRepository extends JpaRepository<InventoryTransaction, Long> {

    List<InventoryTransaction> findByInventoryIdOrderByCreatedAtDesc(Long inventoryId);

    /** 같은 주문이 이미 처리됐는지 판정하는 근거 - 재고 차감/복원의 멱등성 키다. */
    boolean existsByOrderIdAndType(Long orderId, InventoryTransactionType type);

    void deleteByInventoryId(Long inventoryId);
}
