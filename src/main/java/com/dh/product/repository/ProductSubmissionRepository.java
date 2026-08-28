package com.dh.product.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.product.domain.ProductSubmission;
import com.dh.product.domain.SubmissionStatus;

public interface ProductSubmissionRepository extends JpaRepository<ProductSubmission, Long> {
    List<ProductSubmission> findByStatusOrderByIdDesc(SubmissionStatus status);

    List<ProductSubmission> findByProductIdOrderByIdDesc(Long productId);
}
