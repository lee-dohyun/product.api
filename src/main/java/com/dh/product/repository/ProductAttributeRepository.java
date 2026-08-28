package com.dh.product.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.product.domain.ProductAttribute;

public interface ProductAttributeRepository extends JpaRepository<ProductAttribute, Long> {
    List<ProductAttribute> findByProductId(Long productId);

    void deleteByProductId(Long productId);
}
