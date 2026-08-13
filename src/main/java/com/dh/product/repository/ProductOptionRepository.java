package com.dh.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.product.domain.ProductOption;

public interface ProductOptionRepository extends JpaRepository<ProductOption, Long> {
}
