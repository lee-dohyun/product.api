package com.dh.product.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dh.product.domain.Offer;
import com.dh.product.domain.OfferStatus;

public interface OfferRepository extends JpaRepository<Offer, Long> {

    /**
     * offer -> variant -> product, offer -> seller 를 한 번에 끌어온다. 호출부
     * ({@code /internal/offers/resolve})가 상품명과 판매자 상호까지 응답에 담기 때문에,
     * LAZY 로 두면 오퍼 건수만큼 추가 쿼리가 나간다.
     */
    @Query("""
            SELECT o FROM Offer o
            JOIN FETCH o.variant v
            JOIN FETCH v.product
            JOIN FETCH o.seller
            WHERE o.id IN :ids
            """)
    List<Offer> findAllByIdWithVariantAndSeller(@Param("ids") Collection<Long> ids);

    /**
     * variant 기준 조회({@code /internal/offers/resolve?variantIds=}). 대표 오퍼를 고르려면 후보를
     * 전부 봐야 하므로 variant 당 여러 행이 올 수 있다 - 응답에 상품명·판매자 상호를 담으니 같은
     * 이유로 fetch join 한다(product.api#69).
     */
    @Query("""
            SELECT o FROM Offer o
            JOIN FETCH o.variant v
            JOIN FETCH v.product
            JOIN FETCH o.seller
            WHERE v.id IN :variantIds AND o.status = :status
            """)
    List<Offer> findAllByVariantIdInWithVariantAndSeller(
            @Param("variantIds") Collection<Long> variantIds, @Param("status") OfferStatus status);

    List<Offer> findByVariantIdAndStatus(Long variantId, OfferStatus status);

    List<Offer> findByVariantIdIn(Collection<Long> variantIds);
}
