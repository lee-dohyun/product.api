package com.dh.product.repository;

import com.dh.product.domain.Banner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BannerRepository extends JpaRepository<Banner, Long> {
    List<Banner> findAllByIsActiveTrueOrderBySortOrderAsc();
}
