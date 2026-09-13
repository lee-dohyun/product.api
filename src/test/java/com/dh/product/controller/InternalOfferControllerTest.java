package com.dh.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.dh.product.service.offer.InvalidOfferResolveRequestException;
import com.dh.product.service.offer.OfferService;

/**
 * {@code /internal/offers/resolve} 의 파라미터 규칙(product.api#69) — {@code ids} 와
 * {@code variantIds} 중 정확히 하나.
 *
 * <p>둘 다 받으면 어느 기준으로 확정했는지 응답만 보고 알 수 없고, 둘 다 없는데 빈 목록을
 * 돌려주면 호출 버그가 "상품 전부 판매 불가"로 보여 오진하게 된다. 그래서 둘 다 400 이다.
 */
class InternalOfferControllerTest {

    private final OfferService offerService = mock(OfferService.class);
    private final InternalOfferController controller = new InternalOfferController(offerService);

    @Test
    @DisplayName("ids 만 주면 offerId 기준으로 확정한다 — 기존 호출자(계약) 유지")
    void idsOnlyResolvesByOfferId() {
        controller.resolve(List.of(1L, 2L), null);

        verify(offerService).resolveOffers(List.of(1L, 2L));
        verify(offerService, never()).resolveFeaturedOffersByVariant(any());
    }

    @Test
    @DisplayName("variantIds 만 주면 SKU 별 대표 오퍼로 확정한다")
    void variantIdsOnlyResolvesFeaturedOffers() {
        controller.resolve(null, List.of(10L));

        verify(offerService).resolveFeaturedOffersByVariant(List.of(10L));
        verify(offerService, never()).resolveOffers(any());
    }

    @Test
    @DisplayName("둘 다 주면 거부한다")
    void bothParametersAreRejected() {
        assertThatThrownBy(() -> controller.resolve(List.of(1L), List.of(10L)))
                .isInstanceOf(InvalidOfferResolveRequestException.class);
    }

    @Test
    @DisplayName("둘 다 없으면 빈 목록이 아니라 거부한다")
    void neitherParameterIsRejected() {
        assertThatThrownBy(() -> controller.resolve(null, null))
                .isInstanceOf(InvalidOfferResolveRequestException.class);
    }

    @Test
    @DisplayName("파라미터 오류는 400 으로 응답한다 — 409/500 이 아니다")
    void invalidRequestMapsToBadRequest() {
        var response = new ApiExceptionHandler()
                .handleInvalidOfferResolveRequest(new InvalidOfferResolveRequestException("x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
