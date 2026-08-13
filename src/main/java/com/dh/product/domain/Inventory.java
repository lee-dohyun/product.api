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

    // 동시 주문으로 인한 재고 정합성 문제를 막기 위한 낙관적 락
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

    /** @throws IllegalStateException 재고가 부족하면 */
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
