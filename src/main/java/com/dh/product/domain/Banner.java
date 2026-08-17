package com.dh.product.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "banners")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Banner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;
    
    private String subtitle;
    
    private String imageUrl;
    
    private String link;
    
    private String bgColor;

    private Integer sortOrder;
    
    private Boolean isActive;

    @Builder
    public Banner(String title, String subtitle, String imageUrl, String link, String bgColor, Integer sortOrder, Boolean isActive) {
        this.title = title;
        this.subtitle = subtitle;
        this.imageUrl = imageUrl;
        this.link = link;
        this.bgColor = bgColor;
        this.sortOrder = sortOrder;
        this.isActive = isActive;
    }
}
