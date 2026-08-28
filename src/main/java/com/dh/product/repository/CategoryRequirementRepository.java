package com.dh.product.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.product.domain.CategoryRequirement;

public interface CategoryRequirementRepository extends JpaRepository<CategoryRequirement, Long> {
    Optional<CategoryRequirement> findByCategoryId(Long categoryId);
}
