package com.dh.product.service.offer;

import java.util.List;
import java.util.Optional;

import com.dh.product.domain.Offer;

/**
 * 같은 SKU 에 붙은 오퍼 중 대표로 노출할 하나를 고른다(쿠팡 아이템위너에 해당).
 *
 * <p>1P 에서는 후보가 1건뿐이라 선택이 자명하다. 정책(가격·배송·판매자 평점 가중치)은 3P
 * 전환 시점에 정하고, 지금은 <b>인터페이스만 만들어 둔다</b> - 구현을 갈아끼우는 자리를
 * 미리 열어 두되 쓰지도 않을 정책을 지금 지어내지 않기 위함이다(product.api#31).
 */
public interface FeaturedOfferSelector {

    Optional<Offer> select(List<Offer> candidates);
}
