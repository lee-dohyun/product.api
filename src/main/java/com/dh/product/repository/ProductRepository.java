package com.dh.product.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.product.domain.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByCategoryId(Long categoryId);

    List<Product> findByNameContainingIgnoreCase(String name);

    List<Product> findByCategoryIdAndNameContainingIgnoreCase(Long categoryId, String name);

    List<Product> findByOrderByCreatedAtDesc(Pageable pageable);

    List<Product> findByOrderByIdDesc(Pageable pageable);
}
