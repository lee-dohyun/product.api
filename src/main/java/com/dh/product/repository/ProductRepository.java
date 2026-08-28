package com.dh.product.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.product.domain.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByCategoryId(Long categoryId);

    /**
     * 이 카테고리에 달린 상품 수. 카테고리 삭제 차단 판정에 쓴다 - products.category_id 는
     * NOT NULL FK 라, 상품이 달린 카테고리를 그냥 지우면 DB 제약 위반으로 500 이 난다.
     * 사전에 세어 보고 409 로 명확히 거부하기 위한 것이다.
     */
    long countByCategoryId(Long categoryId);

    /**
     * 여러 카테고리에 걸친 상품을 한 번에 조회한다. 메인 페이지의 "카테고리별" 영역이
     * 대분류 하나당 (자기 자신 + 하위 카테고리) 묶음으로 조회하기 위해 쓴다 - 대분류별로
     * 따로 부르면 대분류 수만큼 쿼리가 나간다.
     */
    List<Product> findByCategoryIdIn(Collection<Long> categoryIds);

    List<Product> findByNameContainingIgnoreCase(String name);

    List<Product> findByCategoryIdAndNameContainingIgnoreCase(Long categoryId, String name);

    List<Product> findByOrderByCreatedAtDesc(Pageable pageable);

    List<Product> findByOrderByIdDesc(Pageable pageable);
}
