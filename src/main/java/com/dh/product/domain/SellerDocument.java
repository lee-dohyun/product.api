package com.dh.product.domain;

import java.time.LocalDate;
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

// file_key만 들고 있고 실제 파일은 MinIO에 있다. 사업자등록증·통장사본 등 개인정보라
// public URL을 만들지 않는다 - 조회는 presigned URL로만(admin.front가 발급).
@Entity
@Table(name = "seller_documents")
@Getter
@Setter
@NoArgsConstructor
public class SellerDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    private Seller seller;

    @Column(name = "doc_type", nullable = false, length = 50)
    private String docType;

    @Column(name = "file_key", nullable = false, length = 500)
    private String fileKey;

    @Column(name = "issued_at")
    private LocalDate issuedAt;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public SellerDocument(Seller seller, String docType, String fileKey, LocalDate issuedAt) {
        this.seller = seller;
        this.docType = docType;
        this.fileKey = fileKey;
        this.issuedAt = issuedAt;
    }
}
