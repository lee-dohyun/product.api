package com.dh.product.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // 노출 속성(product.api#28) - 할인율은 저장하지 않고 listPrice와 판매가로 매번 파생한다.
    // ratingAvg/reviewCount는 리뷰 기능이 없는 동안 관리자가 직접 입력하는 비정규화 컬럼이다 -
    // 리뷰 기능이 생기면 실제 집계값으로 교체될 자리다.
    @Column(name = "list_price", precision = 12, scale = 2)
    private BigDecimal listPrice;

    @Column(name = "rating_avg", precision = 2, scale = 1)
    private BigDecimal ratingAvg;

    @Column(name = "review_count", nullable = false)
    private int reviewCount = 0;

    @Column(name = "shipping_badge", length = 20)
    private String shippingBadge;

    @Column(name = "free_shipping", nullable = false)
    private boolean freeShipping = false;

    @Column(length = 100)
    private String brand;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC")
    private List<ProductImage> images = new ArrayList<>();

    // 가격/재고는 더 이상 Product에 없다 - variant(SKU)마다 따로 갖는다. 옵션 없는 단순 상품도
    // "옵션 없는 variant 1개"로 취급해서 항상 이 리스트에 최소 1개는 있다.
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProductVariant> variants = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC")
    private List<ProductOption> options = new ArrayList<>();

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

    public void addImage(ProductImage image) {
        images.add(image);
        image.setProduct(this);
    }

    public void addVariant(ProductVariant variant) {
        variants.add(variant);
    }

    public void addOption(ProductOption option) {
        options.add(option);
    }
}
