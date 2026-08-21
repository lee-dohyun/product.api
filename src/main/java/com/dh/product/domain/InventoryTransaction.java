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
     * balanceAfter를 명시적으로 받는 생성자 (product.api#35).
     *
     * <p>{@link com.dh.product.repository.InventoryRepository#deductQuantity}처럼 DB에서
     * 원자적 벌크 UPDATE로 수량을 바꾸는 경로는 영속성 컨텍스트의 {@code inventory} 엔티티
     * 필드가 갱신되지 않는다({@code inventory.getQuantity()}가 차감 전 값을 그대로 들고 있다).
     * 이런 경로는 실제로 커밋된 수량을 별도로 조회해 여기로 넘겨야 한다.
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
