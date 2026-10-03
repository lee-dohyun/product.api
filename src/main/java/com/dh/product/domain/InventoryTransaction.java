package com.dh.product.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 재고 변동 이력(감사 로그). quantityChange는 부호 있는 값(입고 +, 차감 -),
// balanceAfter는 이 변동을 반영한 시점의 재고 스냅샷.
@Entity
@Table(name = "inventory_transactions")
@Getter
@Setter
@NoArgsConstructor
public class InventoryTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inventory_id", nullable = false)
    private Inventory inventory;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InventoryTransactionType type;

    @Column(name = "quantity_change", nullable = false)
    private Integer quantityChange;

    @Column(name = "balance_after", nullable = false)
    private Integer balanceAfter;

    // order.api의 주문 ID (별개 DB라 FK 없음, ORDER_DEDUCT/ORDER_RESTORE에서만 채워짐)
    @Column(name = "order_id")
    private Long orderId;

    /**
     * ORDER_DEDUCT 행 전용 - 이 차감이 복원으로 되돌려졌는가(product.api#115). 주문의 차감·복원 멱등 판정은
     * "이력이 있는가"가 아니라 "되돌려지지 않은 차감이 있는가"다. 다른 유형의 행에서는 항상 false.
     */
    @Column(nullable = false)
    private boolean reversed = false;

    @Column(length = 200)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public InventoryTransaction(
            Inventory inventory, InventoryTransactionType type, int quantityChange, Long orderId, String reason) {
        this(inventory, type, quantityChange, inventory.getQuantity(), orderId, reason);
    }

    /**
     * 잔고를 직접 받는 생성자 - 벌크 UPDATE 로 수량을 바꾼 경로용이다(product.api#35). 그 경로에서는
     * {@code inventory.getQuantity()} 가 변경 전 값이라 위 생성자를 쓰면 이력의 잔고가 틀린다.
     */
    public InventoryTransaction(
            Inventory inventory, InventoryTransactionType type, int quantityChange, int balanceAfter,
            Long orderId, String reason) {
        this.inventory = inventory;
        this.type = type;
        this.quantityChange = quantityChange;
        this.balanceAfter = balanceAfter;
        this.orderId = orderId;
        this.reason = reason;
    }
}
