package com.dh.product.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dh.product.domain.InventoryTransaction;

public interface InventoryTransactionRepository extends JpaRepository<InventoryTransaction, Long> {

    List<InventoryTransaction> findByInventoryIdOrderByCreatedAtDesc(Long inventoryId);

    /**
     * 이 주문에 되돌려지지 않은 차감이 있는가 - 차감 멱등성의 근거다(product.api#115). 복원된 주문은
     * false 라 다시 차감된다.
     */
    @Query("select count(t) > 0 from InventoryTransaction t where t.orderId = :orderId"
            + " and t.type = com.dh.product.domain.InventoryTransactionType.ORDER_DEDUCT and t.reversed = false")
    boolean existsActiveDeduct(@Param("orderId") Long orderId);

    void deleteByInventoryId(Long inventoryId);
}
