package com.dh.product.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * 이 주문의 되돌려지지 않은 차감을 전부 되돌려진 것으로 바꾸고 바뀐 행 수를 돌려준다. 복원 멱등성의
     * 근거다 - 0 이면 되돌릴 차감이 없다(이미 복원됐거나 차감된 적이 없다). 조회 후 판정이 아니라
     * UPDATE 한 번이라, 동시에 들어온 복원 중 하나만 행을 얻고 나머지는 행 잠금이 풀린 뒤 0 을 받는다.
     */
    @Modifying(flushAutomatically = true)
    @Query("update InventoryTransaction t set t.reversed = true where t.orderId = :orderId"
            + " and t.type = com.dh.product.domain.InventoryTransactionType.ORDER_DEDUCT and t.reversed = false")
    int markActiveDeductsReversed(@Param("orderId") Long orderId);

    void deleteByInventoryId(Long inventoryId);
}
