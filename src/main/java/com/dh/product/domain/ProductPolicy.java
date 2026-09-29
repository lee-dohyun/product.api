package com.dh.product.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 상품 판매 정책 — 과세·KC 인증·배송/반품·판매 기간·구매 수량(product.api#79). 상품 1:1.
 * 왜 products 와 분리했는지는 V20 주석. 용어는 gateway Wiki Glossary §8-2.
 */
@Entity
@Table(name = "product_policies")
@Getter
@Setter
@NoArgsConstructor
public class ProductPolicy {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_type", length = 20)
    private TaxType taxType;

    @Enumerated(EnumType.STRING)
    @Column(name = "kc_cert_type", length = 30)
    private KcCertType kcCertType;

    @Column(name = "kc_cert_number", length = 100)
    private String kcCertNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "shipping_fee_type", length = 20)
    private ShippingFeeType shippingFeeType;

    @Column(name = "shipping_fee", precision = 12, scale = 2)
    private BigDecimal shippingFee;

    @Column(name = "free_shipping_threshold", precision = 12, scale = 2)
    private BigDecimal freeShippingThreshold;

    @Column(name = "shipping_lead_days")
    private Short shippingLeadDays;

    @Column(name = "jeju_extra_fee", precision = 12, scale = 2)
    private BigDecimal jejuExtraFee;

    @Column(name = "island_extra_fee", precision = 12, scale = 2)
    private BigDecimal islandExtraFee;

    @Column(name = "return_shipping_fee", precision = 12, scale = 2)
    private BigDecimal returnShippingFee;

    @Column(name = "exchange_shipping_fee", precision = 12, scale = 2)
    private BigDecimal exchangeShippingFee;

    @Column(name = "return_address", length = 300)
    private String returnAddress;

    @Column(name = "sale_start_at")
    private LocalDateTime saleStartAt;

    @Column(name = "sale_end_at")
    private LocalDateTime saleEndAt;

    @Column(name = "max_purchase_quantity")
    private Integer maxPurchaseQuantity;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ProductPolicy(Long productId) {
        this.productId = productId;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
