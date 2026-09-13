package com.dh.product.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dh.product.dto.OfferDtos.OfferResolveResponse;
import com.dh.product.service.offer.InvalidOfferResolveRequestException;
import com.dh.product.service.offer.OfferService;

/**
 * {@code /internal/variants/resolve} 의 후속(product.api#31). order.api 가 주문 금액을
 * 확정할 때 클러스터 내부망으로만 호출한다 - {@code /internal/**} 은 게이트웨이에 라우트가
 * 없어 외부에서 도달 불가능하다.
 *
 * <p><b>기존 variants/resolve 를 지우지 않는다.</b> order.api 가 아직 그쪽을 부르고 있고,
 * 배포 순서가 product.api → order.api 로 고정돼 있어 한 배포 주기 동안 병행해야 한다.
 * 반대로 하면 order.api 가 없는 엔드포인트를 불러 주문 생성이 전부 실패한다.
 */
@RestController
@RequestMapping("/internal/offers")
public class InternalOfferController {

    private final OfferService offerService;

    public InternalOfferController(OfferService offerService) {
        this.offerService = offerService;
    }

    /**
     * {@code ?ids=} 는 offerId 기준, {@code ?variantIds=} 는 SKU 별 대표 오퍼 기준(product.api#69).
     *
     * <p>둘 중 정확히 하나만 받는다. 둘 다 받으면 어느 기준으로 확정했는지 응답만 보고 알 수 없고,
     * 둘 다 없는데 빈 목록을 돌려주면 호출 버그가 "상품 전부 판매 불가"로 보여 오진하게 된다.
     */
    @GetMapping("/resolve")
    public List<OfferResolveResponse> resolve(
            @RequestParam(value = "ids", required = false) List<Long> ids,
            @RequestParam(value = "variantIds", required = false) List<Long> variantIds) {
        if ((ids == null) == (variantIds == null)) {
            throw new InvalidOfferResolveRequestException("ids 와 variantIds 중 정확히 하나를 지정해야 한다");
        }
        return ids != null
                ? offerService.resolveOffers(ids)
                : offerService.resolveFeaturedOffersByVariant(variantIds);
    }
}
