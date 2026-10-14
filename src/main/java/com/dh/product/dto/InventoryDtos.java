package com.dh.product.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class InventoryDtos {

    public record DeductItem(@NotNull Long variantId, @NotNull @Min(1) Integer quantity) {
    }

    // order.api가 결제 확정 시 클러스터 내부망으로 호출한다 (게이트웨이 라우트 없음, 외부 노출 안 됨)
    public record DeductRequest(
            @NotNull Long orderId,
            @NotEmpty @Valid List<DeductItem> items) {
    }

    public record InventoryBalanceResponse(Long variantId, Integer remainingQuantity) {
    }

    public record RestoreItem(@NotNull Long variantId, @NotNull @Min(1) Integer quantity) {
    }

    /**
     * 상품별 부분 복원(gateway#320). {@code claimKey} 는 order.api 의 클레임 식별자로, 같은 키의 재시도는 한 번만
     * 반영된다. 이 주문이 뺀 수량을 넘겨 되돌리려 하면 409 로 거부한다.
     */
    public record PartialRestoreItem(@NotNull Long variantId, @NotNull @Min(1) Integer quantity) {
    }

    public record PartialRestoreRequest(
            @NotNull Long orderId,
            @NotBlank @Size(max = 64) String claimKey,
            @NotEmpty @Valid List<PartialRestoreItem> items) {
    }

    public record RestoreRequest(
            @NotNull Long orderId,
            @NotEmpty @Valid List<RestoreItem> items) {
    }
}
