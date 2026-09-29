package com.dh.product.service;

import java.util.NoSuchElementException;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.product.config.CacheNames;
import com.dh.product.domain.KcCertType;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductPolicy;
import com.dh.product.domain.ShippingFeeType;
import com.dh.product.domain.TaxType;
import com.dh.product.dto.PolicyDtos.ProductPolicyRequest;
import com.dh.product.dto.PolicyDtos.ProductPolicyResponse;
import com.dh.product.repository.ProductPolicyRepository;
import com.dh.product.repository.ProductRepository;

/**
 * 상품 판매 정책(product.api#79). 소유·수정 가능 판정은 호출부(PartnerProductService)가 먼저 한다.
 *
 * <p>입력 간 모순은 여기서 막는다(→ 400): 유료인데 배송비 없음, 조건부인데 기준 금액 없음, KC 인증
 * 대상인데 번호 없음, 판매 종료가 시작보다 앞섬. "필수 항목이 비었는가"는 검수 규칙(SubmissionValidator)
 * 이 본다 — 임시저장은 빈 칸을 허용해야 하기 때문이다.
 */
@Service
public class ProductPolicyService {

    private final ProductPolicyRepository policyRepository;
    private final ProductRepository productRepository;

    public ProductPolicyService(ProductPolicyRepository policyRepository, ProductRepository productRepository) {
        this.policyRepository = policyRepository;
        this.productRepository = productRepository;
    }

    /** 정책이 아직 없으면 모든 값이 비어 있는 응답(404 가 아니다 — 폼이 빈 칸으로 시작한다). */
    @Transactional(readOnly = true)
    public ProductPolicyResponse get(Long productId) {
        return policyRepository.findById(productId)
                .map(ProductPolicyService::toResponse)
                .orElseGet(() -> toResponse(new ProductPolicy(productId)));
    }

    /**
     * 전체 교체. 상품의 {@code freeShipping}(배지·목록 표시용)을 배송비 정책에서 파생해 맞추므로
     * 상품·메인 캐시를 비운다.
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.PRODUCT, key = "#productId"),
            @CacheEvict(cacheNames = { CacheNames.MAIN_BEST, CacheNames.MAIN_NEW, CacheNames.MAIN_BY_CATEGORY },
                    allEntries = true)
    })
    public ProductPolicyResponse replace(Long productId, ProductPolicyRequest r) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new NoSuchElementException("product not found: " + productId));
        ShippingFeeType shippingType = parse(ShippingFeeType.class, r.shippingFeeType(), "배송비 정책");
        KcCertType kcType = parse(KcCertType.class, r.kcCertType(), "KC 인증 유형");

        if (shippingType == ShippingFeeType.PAID && r.shippingFee() == null) {
            throw new InvalidProductPolicyException("유료 배송은 배송비를 입력해야 합니다.");
        }
        if (shippingType == ShippingFeeType.CONDITIONAL && (r.shippingFee() == null || r.freeShippingThreshold() == null)) {
            throw new InvalidProductPolicyException("조건부 무료배송은 배송비와 무료배송 기준 금액을 입력해야 합니다.");
        }
        if (kcType != null && kcType != KcCertType.NONE && isBlank(r.kcCertNumber())) {
            throw new InvalidProductPolicyException("KC 인증 대상이면 인증번호를 입력해야 합니다.");
        }
        if (r.saleStartAt() != null && r.saleEndAt() != null && !r.saleStartAt().isBefore(r.saleEndAt())) {
            throw new InvalidProductPolicyException("판매 종료 시각은 시작 시각보다 뒤여야 합니다.");
        }

        ProductPolicy p = policyRepository.findById(productId).orElseGet(() -> new ProductPolicy(productId));
        p.setTaxType(parse(TaxType.class, r.taxType(), "과세 구분"));
        p.setKcCertType(kcType);
        p.setKcCertNumber(kcType == null || kcType == KcCertType.NONE ? null : r.kcCertNumber().trim());
        p.setShippingFeeType(shippingType);
        // 무료배송이면 배송비·기준 금액을 비운다 — 남겨 두면 "무료인데 배송비 3000원" 같은 모순이 화면에 나간다.
        boolean free = shippingType == ShippingFeeType.FREE;
        p.setShippingFee(free ? null : r.shippingFee());
        p.setFreeShippingThreshold(shippingType == ShippingFeeType.CONDITIONAL ? r.freeShippingThreshold() : null);
        p.setShippingLeadDays(r.shippingLeadDays());
        p.setJejuExtraFee(r.jejuExtraFee());
        p.setIslandExtraFee(r.islandExtraFee());
        p.setReturnShippingFee(r.returnShippingFee());
        p.setExchangeShippingFee(r.exchangeShippingFee());
        p.setReturnAddress(isBlank(r.returnAddress()) ? null : r.returnAddress().trim());
        p.setSaleStartAt(r.saleStartAt());
        p.setSaleEndAt(r.saleEndAt());
        p.setMaxPurchaseQuantity(r.maxPurchaseQuantity());
        policyRepository.save(p);

        if (shippingType != null) {
            product.setFreeShipping(free);
        }
        return toResponse(p);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw, String label) {
        if (isBlank(raw)) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidProductPolicyException(label + " 값을 알 수 없습니다: " + raw);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }

    static ProductPolicyResponse toResponse(ProductPolicy p) {
        return new ProductPolicyResponse(p.getProductId(), name(p.getTaxType()), name(p.getKcCertType()),
                p.getKcCertNumber(), name(p.getShippingFeeType()), p.getShippingFee(), p.getFreeShippingThreshold(),
                p.getShippingLeadDays(), p.getJejuExtraFee(), p.getIslandExtraFee(), p.getReturnShippingFee(),
                p.getExchangeShippingFee(), p.getReturnAddress(), p.getSaleStartAt(), p.getSaleEndAt(),
                p.getMaxPurchaseQuantity());
    }

}
