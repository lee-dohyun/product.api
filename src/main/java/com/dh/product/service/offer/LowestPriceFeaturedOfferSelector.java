package com.dh.product.service.offer;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.dh.product.domain.Offer;
import com.dh.product.domain.OfferStatus;

/**
 * 기본 구현 - ACTIVE 오퍼 중 최저가, 동가면 먼저 등록된 것.
 *
 * <p>1P 에서는 후보가 1건뿐이라 어떤 정책을 써도 결과가 같다. 최저가를 고른 것은 "정책을
 * 정했다"는 뜻이 아니라 후보가 여러 개일 때 결과가 결정적이어야 하기 때문이다 - 3P 도입 시
 * 실제 정책(배송·판매자 평점 등)으로 이 클래스를 교체한다.
 */
@Component
public class LowestPriceFeaturedOfferSelector implements FeaturedOfferSelector {

    @Override
    public Optional<Offer> select(List<Offer> candidates) {
        return candidates.stream()
                .filter(o -> o.getStatus() == OfferStatus.ACTIVE)
                .min(Comparator.comparing(Offer::getPrice).thenComparing(Offer::getId));
    }
}
