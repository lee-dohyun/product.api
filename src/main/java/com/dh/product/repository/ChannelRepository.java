package com.dh.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.dh.product.domain.Channel;

public interface ChannelRepository extends JpaRepository<Channel, Long> {
}
