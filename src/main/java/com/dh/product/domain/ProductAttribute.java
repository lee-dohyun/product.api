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

// 상품별 고시 항목 값. 어떤 code 가 필요한지는 CategoryRequirement.requiredAttributes 가 정한다.
@Entity
@Table(name = "product_attributes")
@Getter
@Setter
@NoArgsConstructor
public class ProductAttribute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "attribute_code", nullable = false, length = 50)
    private String attributeCode;

    @Column(name = "attribute_value", length = 500)
    private String attributeValue;

    public ProductAttribute(Product product, String attributeCode, String attributeValue) {
        this.product = product;
        this.attributeCode = attributeCode;
        this.attributeValue = attributeValue;
    }
}
