package com.dh.product.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "sellers")
@Getter
@Setter
@NoArgsConstructor
public class Seller {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "business_registration_no", nullable = false, length = 20)
    private String businessRegistrationNo;

    @Column(name = "mail_order_sales_no", length = 30)
    private String mailOrderSalesNo;

    @Column(name = "representative_name", nullable = false, length = 100)
    private String representativeName;

    @Column(nullable = false, length = 300)
    private String address;

    @Column(nullable = false, length = 30)
    private String phone;

    @Column(nullable = false, length = 200)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SellerStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SellerType type;

    @Column(name = "settlement_bank", length = 50)
    private String settlementBank;

    @Column(name = "settlement_account", length = 50)
    private String settlementAccount;

    @Column(name = "shipping_origin_address", length = 300)
    private String shippingOriginAddress;

    @Column(name = "return_address", length = 300)
    private String returnAddress;

    @Column(name = "shipping_fee_policy", length = 500)
    private String shippingFeePolicy;

    @Column(name = "cs_contact", length = 100)
    private String csContact;

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
