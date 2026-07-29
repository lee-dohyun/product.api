package com.dh.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.product.domain.Category;

public interface CategoryRepository extends JpaRepository<Category, Long> {
}
