package com.dh.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.product.domain.ProductOptionValue;

public interface ProductOptionValueRepository extends JpaRepository<ProductOptionValue, Long> {
}
