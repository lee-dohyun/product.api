package com.dh.product.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dh.product.domain.ProductVariant;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    List<ProductVariant> findByProductId(Long productId);

    List<ProductVariant> findByProductIdIn(Collection<Long> productIds);

    // product를 fetch join하는 이유: 호출자가 상품명까지 함께 쓰는데 LAZY로 두면 건수만큼 추가 쿼리가 나간다.
    @Query("select v from ProductVariant v join fetch v.product where v.id in :ids")
    List<ProductVariant> findAllByIdWithProduct(@Param("ids") Collection<Long> ids);
}
