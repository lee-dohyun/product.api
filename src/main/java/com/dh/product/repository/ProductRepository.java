package com.dh.product.repository;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.product.domain.Product;
import com.dh.product.domain.ProductStatus;

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

    // 메인 페이지용 LIVE 한정 조회(product.api#74). 조회 후 걸러내면 limit 개수보다 적게 나오므로
    // 상태 조건을 쿼리에 넣는다.
    List<Product> findByStatusOrderByCreatedAtDesc(ProductStatus status, Pageable pageable);

    List<Product> findByStatusOrderByIdDesc(ProductStatus status, Pageable pageable);

    List<Product> findByCategoryIdInAndStatus(Collection<Long> categoryIds, ProductStatus status);

    /** 파트너 포털의 "내 상품" 목록(product.api#75). 상태와 무관하게 그 판매자의 전부. */
    List<Product> findBySellerIdOrderByIdDesc(Long sellerId);

    /**
     * 파트너의 수정·제출 판정과 쓰기 사이에 다른 요청이 끼어들지 못하게 상품 행을 잠근다(product.api#75).
     * 잠그지 않으면 "검수 중이 아님"을 확인한 직후 제출이 커밋돼, 심사자가 본 것과 다른 내용이 승인될 수 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);
}
