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

    List<Offer> findByVariantIdAndStatus(Long variantId, OfferStatus status);

    List<Offer> findByVariantIdIn(Collection<Long> variantIds);
}
