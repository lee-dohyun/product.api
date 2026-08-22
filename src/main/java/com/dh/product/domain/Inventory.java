package com.dh.product.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// variant(SKU) 1개당 재고 1행.
@Entity
@Table(name = "inventories")
@Getter
@Setter
@NoArgsConstructor
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id", nullable = false, unique = true)
    private ProductVariant variant;

    @Column(nullable = false)
    private Integer quantity;

    // 낙관적 락 (product.api#35: 주문 차감 경로는 더 이상 이걸 쓰지 않는다).
    //
    // 서로 다른 주문이 동시에 같은 재고 행을 놓고 경쟁하는 케이스는 InventoryRepository#deductQuantity의
    // 원자적 조건부 UPDATE(WHERE quantity >= amount)로 처리한다 - 벌크 UPDATE라 이 필드를 건드리지
    // 않는다. 이 필드는 여전히 InventoryService(restock 초기화/관리자 수동 조정)와 InventoryDeductor
    // 의 restore(주문 취소 시 재고 복원) 경로의 read-modify-write(save)를 보호한다. 이 경로들은
    // "이미 사용자가 결제까지 마친 뒤"라 차감만큼 격렬한 동시 경쟁이 나지 않고, 방향(증가/조정)도
    // 매진과 무관해 낙관적 락 재시도 실패가 나더라도 요청을 다시 보내는 것으로 충분하다고 판단해
    // 남겨뒀다. ApiExceptionHandler가 ObjectOptimisticLockingFailureException을 409로 매핑하므로
    // 이 경로들도 최소한 raw 500 대신 깨끗한 응답은 받는다.
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Inventory(ProductVariant variant, int quantity) {
        this.variant = variant;
        this.quantity = quantity;
    }

    /**
     * @throws IllegalStateException 재고가 부족하면
     *
     * <p>주문 차감 경로({@code InventoryDeductor#deductOnce})는 동시성 문제로 이 메서드를
     * 더 이상 쓰지 않는다 (product.api#35) - 대신 {@code InventoryRepository#deductQuantity}의
     * 원자적 조건부 UPDATE를 쓴다. 이 메서드는 "재고보다 많이 뺄 수 없다"는 도메인 불변식을
     * 문서화하고 단위 테스트({@code InventoryTest})로 검증하기 위해 남겨뒀다 - 새 차감 경로에
     * 다시 끌어다 쓰지 말 것(동시 주문 경합을 막지 못한다).
     */
    public void deduct(int amount) {
        if (quantity < amount) {
            throw new IllegalStateException(
                    "재고가 부족합니다: variantId=" + variant.getId() + ", 요청=" + amount + ", 재고=" + quantity);
        }
        quantity -= amount;
    }

    public void restore(int amount) {
        quantity += amount;
    }

    public void setTo(int newQuantity) {
        quantity = newQuantity;
    }
}
