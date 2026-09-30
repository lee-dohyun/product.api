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

    /**
     * 판매자가 판매 가능(ACTIVE)이 아닌 상품 id(product.api#100). 정지·해지 판매자의 상품은 노출은 두고
     * 구매만 막는다 — 판정은 PurchaseRules 가 이 결과로 한다. 엔티티 대신 id 만 받는 것은 장바구니
     * 경로가 트랜잭션 밖이라(open-in-view=false) LAZY seller 를 건드릴 수 없기 때문이다.
     */
    @Query("SELECT p.id FROM Product p WHERE p.id IN :ids AND p.seller.status <> com.dh.product.domain.SellerStatus.ACTIVE")
    List<Long> findIdsWithInactiveSeller(@Param("ids") Collection<Long> ids);

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

    /** 카테고리 목록 + 검색어(product.api#102) — 카테고리는 (자기 + 하위) 묶음으로 넘긴다. */
    List<Product> findByCategoryIdInAndNameContainingIgnoreCase(Collection<Long> categoryIds, String name);

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

    /** 관리자 목록 상태 필터(admin.front#50). */
    List<Product> findByStatusOrderByIdDesc(ProductStatus status);

    List<Product> findAllByOrderByIdDesc();
}
