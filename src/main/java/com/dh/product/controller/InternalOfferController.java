package com.dh.product.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dh.product.dto.OfferDtos.OfferResolveResponse;
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

    @GetMapping("/resolve")
    public List<OfferResolveResponse> resolve(@RequestParam("ids") List<Long> ids) {
        return offerService.resolveOffers(ids);
    }
}
