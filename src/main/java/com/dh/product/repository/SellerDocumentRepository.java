package com.dh.product.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.product.domain.SellerDocument;

public interface SellerDocumentRepository extends JpaRepository<SellerDocument, Long> {
    List<SellerDocument> findBySellerIdOrderByIdAsc(Long sellerId);
}
