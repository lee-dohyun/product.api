package com.dh.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.product.domain.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
