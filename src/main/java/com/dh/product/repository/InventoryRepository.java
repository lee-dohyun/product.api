package com.dh.product.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dh.product.domain.Inventory;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByVariantId(Long variantId);

    List<Inventory> findByVariantIdIn(Collection<Long> variantIds);

    /**
     * 동시에 들어오는 서로 다른 주문의 재고 차감을 위한 원자적 조건부 UPDATE (product.api#35).
     *
     * <p>기존에는 엔티티를 읽고({@code Inventory.deduct}) {@code @Version}으로 저장하는
     * read-modify-write 패턴을 썼다. 이 방식은 서로 다른 두 주문이 동시에 같은 재고 행을
     * 놓고 경쟁하면 버전 경합에서 진 쪽이 {@code ObjectOptimisticLockingFailureException}을
     * 던지는데, {@code ApiExceptionHandler}는 이 타입을 처리하지 않아 매진 상황에서 대부분의
     * 구매자가 깨끗한 409 대신 처리되지 않은 500을 받는다.
     *
     * <p>단일 SQL문의 {@code WHERE quantity >= :amount} 조건이 "확인"과 "차감"을 하나의 원자적
     * 연산으로 묶으므로 버전 충돌 자체가 발생하지 않는다. 영향받은 행 수가 0이면 재고 부족으로
     * 취급한다. {@code @Version}을 증가시키지 않는 벌크 UPDATE라 다른 필드(예: restock/조정
     * 경로가 쓰는 낙관적 락)와 충돌하지 않는다.
     */
    @Modifying
    @Query("UPDATE Inventory i SET i.quantity = i.quantity - :amount "
            + "WHERE i.id = :id AND i.quantity >= :amount")
    int deductQuantity(@Param("id") Long id, @Param("amount") int amount);

    /**
     * {@link #deductQuantity}로 원자적 차감을 마친 뒤 실제 커밋된(같은 트랜잭션 내) 수량을
     * 읽어온다. 스칼라 프로젝션이라 영속성 컨텍스트 1차 캐시(식별자 기반 identity map)를 타지
     * 않고 항상 DB를 조회하므로, 벌크 UPDATE 직후에도 정확한 값을 돌려준다.
     */
    @Query("SELECT i.quantity FROM Inventory i WHERE i.id = :id")
    int getQuantity(@Param("id") Long id);
}
