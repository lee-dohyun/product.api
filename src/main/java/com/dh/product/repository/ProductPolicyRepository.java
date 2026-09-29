package com.dh.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.product.domain.ProductPolicy;

public interface ProductPolicyRepository extends JpaRepository<ProductPolicy, Long> {
}
