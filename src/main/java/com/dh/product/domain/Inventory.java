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

    // 복원·관리자 조정(읽고-고쳐-쓰기)을 보호하는 낙관적 락. 주문 차감은 이걸로 경합을 가리지 않고
    // InventoryRepository#deductIfEnough 의 조건부 UPDATE 를 쓰되 버전은 함께 올린다(product.api#35).
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
     * 주문 차감 경로는 이 메서드를 쓰지 않는다 - 동시 주문 경합을 못 막는다(product.api#35,
     * {@code InventoryRepository#deductIfEnough} 를 쓸 것).
     *
     * @throws IllegalStateException 재고가 부족하면
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
