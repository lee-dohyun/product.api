package com.dh.product.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.product.domain.ProductSubmission;
import com.dh.product.domain.SubmissionStatus;

public interface ProductSubmissionRepository extends JpaRepository<ProductSubmission, Long> {
    List<ProductSubmission> findByStatusOrderByIdDesc(SubmissionStatus status);

    List<ProductSubmission> findByProductIdOrderByIdDesc(Long productId);

    /** 파트너 목록(product.api#78) - 상품 여러 개의 제출 이력을 한 번에. 상품별 최신은 호출부가 id 최대값으로 고른다. */
    List<ProductSubmission> findByProductIdIn(Collection<Long> productIds);
}
