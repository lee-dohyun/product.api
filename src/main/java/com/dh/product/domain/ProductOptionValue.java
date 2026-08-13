package com.dh.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 옵션 축의 실제 값(예: 색상 옵션의 "블랙"). variant는 옵션마다 이 값을 하나씩 골라 조합된다.
@Entity
@Table(name = "product_option_values")
@Getter
@Setter
@NoArgsConstructor
public class ProductOptionValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "option_id", nullable = false)
    private ProductOption option;

    @Column(nullable = false, length = 50)
    private String value;

    @Column(name = "sort_order", nullable = false)
    private Short sortOrder = 0;

    public ProductOptionValue(ProductOption option, String value) {
        this.option = option;
        this.value = value;
    }
}
