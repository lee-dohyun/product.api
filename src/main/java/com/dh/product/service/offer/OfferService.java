package com.dh.product.service.offer;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.product.domain.Inventory;
import com.dh.product.domain.Offer;
import com.dh.product.domain.OfferStatus;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductPolicy;
import com.dh.product.domain.ProductVariant;
import com.dh.product.dto.OfferDtos.OfferResolveResponse;
import com.dh.product.repository.InventoryRepository;
import com.dh.product.repository.OfferRepository;
import com.dh.product.service.PurchaseRules;

@Service
@Transactional(readOnly = true)
public class OfferService {

    private final OfferRepository offerRepository;
    private final FeaturedOfferSelector featuredOfferSelector;
    private final PurchaseRules purchaseRules;
    private final InventoryRepository inventoryRepository;

    public OfferService(OfferRepository offerRepository, FeaturedOfferSelector featuredOfferSelector,
            PurchaseRules purchaseRules, InventoryRepository inventoryRepository) {
        this.offerRepository = offerRepository;
        this.featuredOfferSelector = featuredOfferSelector;
        this.purchaseRules = purchaseRules;
        this.inventoryRepository = inventoryRepository;
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
        return toResolveResponses(offerRepository.findAllByIdWithVariantAndSeller(offerIds));
    }

    /**
     * variant 마다 <b>대표 오퍼</b>를 골라 가격·상품·판매자를 확정해 돌려준다(product.api#69).
     *
     * <p>장바구니와 주문을 만드는 프론트(product.front)가 아직 {@code variantId} 만 알고 있어서,
     * order.api 가 오퍼 기준으로 금액·판매자를 확정하려면 "이 SKU 를 누구 오퍼로 살지"를 서버가
     * 골라 줘야 한다. 고르는 규칙은 {@link FeaturedOfferSelector} 하나로 모은다 - 대표가
     * 계산({@link #representativePrice})과 주문 확정이 서로 다른 오퍼를 보면 화면에 보인 가격과
     * 결제 금액이 갈라진다.
     *
     * <p>ACTIVE 오퍼가 없는 variant 와 존재하지 않는 id 는 결과에서 빠진다({@link #resolveOffers}
     * 와 같은 계약) - 호출자가 요청 개수와 대조해 누락을 판정한다.
     */
    public List<OfferResolveResponse> resolveFeaturedOffersByVariant(Collection<Long> variantIds) {
        if (variantIds == null || variantIds.isEmpty()) {
            return List.of();
        }
        List<Offer> featured = offerRepository
                .findAllByVariantIdInWithVariantAndSeller(variantIds, OfferStatus.ACTIVE).stream()
                .collect(Collectors.groupingBy(o -> o.getVariant().getId()))
                .values().stream()
                .map(featuredOfferSelector::select)
                .flatMap(Optional::stream)
                .toList();
        return toResolveResponses(featured);
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
     * SKU 가격이 바뀌면 그 상품 판매자의 오퍼 가격도 맞춘다. 화면의 대표가는 오퍼 가격에서 나오므로
     * ({@link #representativePrice}) SKU 만 고치면 "저장했는데 가격이 그대로"가 된다(product.api#75 리뷰).
     * 다른 판매자의 오퍼(3P)는 건드리지 않는다 - 그 판매자가 정한 가격이다.
     *
     * <p>클래스 레벨이 {@code readOnly = true} 라 쓰기 메서드는 {@code @Transactional} 을 따로 붙인다(캐논 §3).
     */
    @Transactional
    public void syncSellerOfferPrice(Product product, ProductVariant variant) {
        offerRepository.findByVariantIdIn(List.of(variant.getId())).stream()
                .filter(o -> o.getSeller().getId().equals(product.getSeller().getId()))
                .forEach(o -> o.setPrice(variant.getPrice()));
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
        return representativePrice(active, featuredOffersOf(active.stream().map(ProductVariant::getId).toList()));
    }

    /**
     * 대표 오퍼 맵을 이미 가진 호출자용(product.api#72). 여러 상품의 대표가를 계산할 때는 목록 전체의
     * 활성 variant 에 대해 {@link #featuredOffersOfActive} 를 <b>한 번</b> 부르고 그 맵을 넘긴다 —
     * 상품마다 {@link #representativePrice(List)} 를 부르면 상품 수만큼 오퍼 쿼리가 나간다(N+1).
     * 실제로 129건 목록이 1.2~2.2초까지 늘어 게이트웨이 3초 타임리미터에 걸렸다.
     */
    public BigDecimal representativePrice(List<ProductVariant> variants, Map<Long, Offer> featuredByVariantId) {
        return variants.stream()
                .filter(ProductVariant::isActive)
                .map(v -> {
                    Offer offer = featuredByVariantId.get(v.getId());
                    return offer != null ? offer.getPrice() : v.getPrice();
                })
                .min(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
    }

    /** 여러 상품의 variant 묶음에서 활성 variant 전부의 대표 오퍼를 쿼리 한 번으로 모은다(product.api#72). */
    public Map<Long, Offer> featuredOffersOfActive(Collection<List<ProductVariant>> variantGroups) {
        List<Long> activeIds = variantGroups.stream()
                .flatMap(List::stream)
                .filter(ProductVariant::isActive)
                .map(ProductVariant::getId)
                .toList();
        return featuredOffersOf(activeIds);
    }

    /**
     * 정책·판매자 정지 조회를 상품 묶음으로 <b>한 번씩만</b> 해서 응답으로 옮긴다(product.api#108).
     * 오퍼마다 조회하면 주문 항목 수만큼 쿼리가 나간다(product.api#72 와 같은 실수).
     */
    private List<OfferResolveResponse> toResolveResponses(List<Offer> offers) {
        if (offers.isEmpty()) {
            return List.of();
        }
        List<Long> productIds = offers.stream()
                .map(o -> o.getVariant().getProduct().getId())
                .distinct()
                .toList();
        Map<Long, ProductPolicy> policies = purchaseRules.policiesOf(productIds);
        Set<Long> suspended = purchaseRules.saleSuspendedOf(productIds);
        // 재고도 variant 묶음으로 한 번만 읽는다. 원본 테이블을 직접 읽는다 - 상품 상세 캐시의 재고는
        // 표시용이라 주문 판단에 쓰지 않는다(gateway Wiki ADR-0006).
        List<Long> variantIds = offers.stream().map(o -> o.getVariant().getId()).distinct().toList();
        Map<Long, Integer> stockByVariant = inventoryRepository.findByVariantIdIn(variantIds).stream()
                .collect(Collectors.toMap(i -> i.getVariant().getId(), Inventory::getQuantity));
        return offers.stream()
                .map(o -> toResolveResponse(o, policies.get(o.getVariant().getProduct().getId()), suspended,
                        stockByVariant.getOrDefault(o.getVariant().getId(), 0)))
                .toList();
    }

    private OfferResolveResponse toResolveResponse(Offer o, ProductPolicy policy, Set<Long> suspended,
            int stockQuantity) {
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
                // 오퍼 상태 + variants/resolve 와 동일한 구매 가능 판정. 판정식은 PurchaseRules 에만 둔다.
                o.getStatus() == OfferStatus.ACTIVE
                        && purchaseRules.purchasable(o.getVariant(), policy, suspended),
                purchaseRules.maxPurchaseQuantity(policy),
                stockQuantity);
    }
}
