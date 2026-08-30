package com.dh.product.service.offer;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.product.domain.Offer;
import com.dh.product.domain.OfferStatus;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductVariant;
import com.dh.product.dto.OfferDtos.OfferResolveResponse;
import com.dh.product.repository.OfferRepository;

@Service
@Transactional(readOnly = true)
public class OfferService {

    private final OfferRepository offerRepository;
    private final FeaturedOfferSelector featuredOfferSelector;

    public OfferService(OfferRepository offerRepository, FeaturedOfferSelector featuredOfferSelector) {
        this.offerRepository = offerRepository;
        this.featuredOfferSelector = featuredOfferSelector;
    }

    /**
     * offerId 만으로 가격·상품·판매자를 확정해 돌려준다.
     *
     * <p>존재하지 않는 id 는 결과에서 빠지므로 <b>호출자가 요청한 개수와 대조해 누락을 판정해야
     * 한다</b> - {@code resolveVariants} 와 같은 계약이다. 여기서 예외를 던지지 않는 이유는
     * 한 건이 사라졌다고 주문 전체를 500 으로 떨어뜨리면 어느 항목이 문제인지 호출자가 알 수
     * 없기 때문이다.
     */
    public List<OfferResolveResponse> resolveOffers(Collection<Long> offerIds) {
        if (offerIds == null || offerIds.isEmpty()) {
            return List.of();
        }
        return offerRepository.findAllByIdWithVariantAndSeller(offerIds).stream()
                .map(this::toResolveResponse)
                .toList();
    }

    /** SKU 하나의 대표 오퍼. 1P 에서는 후보가 1건이라 자명하다. */
    public Optional<Offer> featuredOfferOf(Long variantId) {
        return featuredOfferSelector.select(
                offerRepository.findByVariantIdAndStatus(variantId, OfferStatus.ACTIVE));
    }

    /**
     * variant 여러 개의 대표 오퍼를 한 번에. 목록 화면이 SKU 마다 따로 부르면 N+1 이 된다.
     * 대표 오퍼가 없는 variant 는 결과에서 빠진다.
     */
    public Map<Long, Offer> featuredOffersOf(Collection<Long> variantIds) {
        if (variantIds == null || variantIds.isEmpty()) {
            return Map.of();
        }
        return offerRepository.findByVariantIdIn(variantIds).stream()
                .collect(Collectors.groupingBy(o -> o.getVariant().getId()))
                .entrySet().stream()
                .flatMap(e -> featuredOfferSelector.select(e.getValue())
                        .map(o -> Map.entry(e.getKey(), o))
                        .stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * SKU 를 만들 때 그 상품 주인(1P 는 자사) 명의의 오퍼를 함께 만든다.
     *
     * <p>오퍼가 없는 SKU 를 허용하면 "판매 단위인데 파는 사람이 없는" 상태가 되고, 오퍼 가격을
     * 쓰는 조회 경로에서 그 SKU 만 가격이 사라진다. V17 이 기존 variant 를 전부 백필한 것과
     * 같은 이유로 새로 생기는 variant 도 여기서 짝을 맞춘다.
     *
     * <p>클래스 레벨이 {@code readOnly = true} 라 이 메서드에는 {@code @Transactional} 을
     * 따로 붙여야 한다 - 안 붙이면 INSERT 가 조용히 사라진다(캐논 §3).
     */
    @Transactional
    public Offer createFirstPartyOffer(Product product, ProductVariant variant) {
        Offer offer = new Offer(product.getSeller(), variant, variant.getPrice(), OfferStatus.ACTIVE);
        offer.setFreeShipping(product.isFreeShipping());
        return offerRepository.save(offer);
    }

    /**
     * 활성 variant 들의 대표 가격(= 최저 대표오퍼가). 오퍼가 아직 없는 variant 는 그 variant 의
     * 가격으로 넘어간다 - V17 백필과 {@link #createFirstPartyOffer} 로 그런 variant 는 없어야
     * 하지만, 하나 빠졌다고 상품 가격이 0원으로 보이는 것보다는 낫다.
     */
    public BigDecimal representativePrice(List<ProductVariant> variants) {
        List<ProductVariant> active = variants.stream().filter(ProductVariant::isActive).toList();
        if (active.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Map<Long, Offer> featured = featuredOffersOf(active.stream().map(ProductVariant::getId).toList());
        return active.stream()
                .map(v -> {
                    Offer offer = featured.get(v.getId());
                    return offer != null ? offer.getPrice() : v.getPrice();
                })
                .min(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
    }

    private OfferResolveResponse toResolveResponse(Offer o) {
        return new OfferResolveResponse(
                o.getId(),
                o.getVariant().getId(),
                o.getVariant().getProduct().getId(),
                o.getVariant().getProduct().getName(),
                o.getSeller().getId(),
                o.getSeller().getName(),
                o.getPrice(),
                o.getShippingFee(),
                o.isFreeShipping(),
                o.getLeadTimeDays(),
                o.getStatus() == OfferStatus.ACTIVE);
    }
}
