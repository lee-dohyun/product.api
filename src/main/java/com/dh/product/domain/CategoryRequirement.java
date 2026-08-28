package com.dh.product.domain;

import java.math.BigDecimal;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 카테고리가 상품 등록의 나머지를 전부 결정한다 - 필수 고시 항목, 필요 인허가, 판매권한.
// 조사한 모든 몰에서 등록 폼의 첫 필드가 카테고리인 것이 UI 편의가 아니라 이 때문이다.
@Entity
@Table(name = "category_requirements")
@Getter
@Setter
@NoArgsConstructor
public class CategoryRequirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    // JSON 배열 문자열. jsonb 가 아닌 이유는 V16 주석 참고 - 질의 대상이 아니라 통째로 읽는 값이다.
    @Column(name = "required_attributes", nullable = false, columnDefinition = "TEXT")
    private String requiredAttributes = "[]";

    @Column(name = "required_documents", nullable = false, columnDefinition = "TEXT")
    private String requiredDocuments = "[]";

    @Column(name = "commission_rate", precision = 5, scale = 2)
    private BigDecimal commissionRate;

    @Column(nullable = false)
    private boolean restricted = false;

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
}
