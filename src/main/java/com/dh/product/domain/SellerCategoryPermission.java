package com.dh.product.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

// 판매권한(게이트 04). restricted 카테고리는 이 행이 있어야 제출이 통과한다.
@Entity
@Table(name = "seller_category_permissions")
@Getter
@Setter
@NoArgsConstructor
public class SellerCategoryPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    private Seller seller;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "granted_by", nullable = false, length = 200)
    private String grantedBy;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private LocalDateTime grantedAt;

    @PrePersist
    void onCreate() {
        grantedAt = LocalDateTime.now();
    }

    public SellerCategoryPermission(Seller seller, Category category, String grantedBy) {
        this.seller = seller;
        this.category = category;
        this.grantedBy = grantedBy;
    }
}
