package com.dh.product.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public class InventoryDtos {

    public record DeductItem(@NotNull Long productId, @NotNull @Min(1) Integer quantity) {
    }

    // order.api가 결제 확정 시 클러스터 내부망으로 호출한다 (게이트웨이 라우트 없음, 외부 노출 안 됨)
    public record DeductRequest(
            @NotNull Long orderId,
            @NotEmpty @Valid List<DeductItem> items) {
    }

    public record InventoryBalanceResponse(Long productId, Integer remainingQuantity) {
    }
}
