package com.dh.product.repository;

import java.time.LocalDateTime;
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
     * 재고가 충분할 때만 빼는 조건부 UPDATE 한 번 - 바뀐 행이 0 이면 재고 부족이다(product.api#35).
     *
     * <p>읽고-고쳐-쓰는 방식({@link Inventory#deduct})은 서로 다른 주문이 같은 행에 몰리면 한쪽만
     * 커밋되고 나머지는 낙관적 락 실패로 떨어졌다 - 재고가 넉넉해도 그랬다. 확인과 차감을 한 문장으로
     * 묶으면 뒤에 온 주문은 행 잠금이 풀리길 기다렸다가 그 시점의 수량으로 다시 판정받는다.
     *
     * <p>버전을 함께 올린다. 복원·관리자 조정은 여전히 {@code @Version} 에 기댄 읽고-고쳐-쓰기라,
     * 여기서 버전을 안 올리면 그쪽이 "읽은 뒤 끼어든 차감"을 모르고 덮어쓴다. HQL 의
     * {@code update versioned} 는 쓰지 말 것 - Hibernate 6.6 이 캐시된 쿼리 트리를 실행 때마다 고쳐서
     * 동시 호출에서 {@code ConcurrentModificationException} 이 난다(동시성 테스트로 확인).
     * 벌크 UPDATE 는 {@code @PreUpdate} 를 타지 않으므로 {@code updatedAt} 도 직접 넣는다.
     */
    @Modifying(flushAutomatically = true)
    @Query("update Inventory i set i.quantity = i.quantity - :amount, i.version = i.version + 1,"
            + " i.updatedAt = :now where i.id = :id and i.quantity >= :amount")
    int deductIfEnough(@Param("id") Long id, @Param("amount") int amount, @Param("now") LocalDateTime now);

    /**
     * 벌크 UPDATE 뒤의 수량을 DB 에서 다시 읽는다. 엔티티로 읽으면 영속성 컨텍스트에 남아 있는
     * 차감 전 값이 나오므로 스칼라로 조회한다.
     */
    @Query("select i.quantity from Inventory i where i.id = :id")
    int findQuantityById(@Param("id") Long id);
}
