package com.dh.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.dh.product.dto.InventoryDtos.DeductItem;
import com.dh.product.dto.InventoryDtos.InventoryBalanceResponse;

/**
 * 진입점의 분기만 본다 (Redmine posselect #211). 동시 요청이 유니크 제약에 걸리는 경로는 실제
 * DB로 결정적으로 재현하기 어려워서 여기서 목으로 검증하고, 차감 로직 자체의 검증은
 * {@link InventoryDeductionIntegrationTest}가 실제 커밋된 행으로 한다.
 */
@ExtendWith(MockitoExtension.class)
class InventoryDeductionServiceTest {

    private static final Long ORDER_ID = 42L;
    private static final Long VARIANT_ID = 7L;

    @Mock
    private InventoryDeductor deductor;

    @InjectMocks
    private InventoryDeductionService service;

    private final List<DeductItem> items = List.of(new DeductItem(VARIANT_ID, 3));
    private final List<InventoryBalanceResponse> balances =
            List.of(new InventoryBalanceResponse(VARIANT_ID, 7));

    @Test
    @DisplayName("동시 요청이 유니크 제약에 걸리면 실패가 아니라 현재 잔고 반환으로 처리한다")
    void 동시_중복_요청은_성공으로_취급한다() {
        when(deductor.deductOnce(ORDER_ID, items))
                .thenThrow(new DataIntegrityViolationException("uq_inventory_transactions_order_deduct"));
        when(deductor.currentBalances(items)).thenReturn(balances);

        assertThat(service.deductForOrder(ORDER_ID, items)).isEqualTo(balances);
    }

    @Test
    @DisplayName("정상 경로에서는 잔고를 다시 조회하지 않는다")
    void 정상_경로는_그대로_위임한다() {
        when(deductor.deductOnce(ORDER_ID, items)).thenReturn(balances);

        assertThat(service.deductForOrder(ORDER_ID, items)).isEqualTo(balances);
        verify(deductor, never()).currentBalances(any());
    }
}
