package com.dh.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataIntegrityViolationException;

import com.dh.product.domain.Inventory;
import com.dh.product.domain.InventoryTransaction;
import com.dh.product.domain.InventoryTransactionType;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductVariant;
import com.dh.product.dto.InventoryDtos.DeductItem;
import com.dh.product.dto.InventoryDtos.InventoryBalanceResponse;
import com.dh.product.repository.InventoryRepository;
import com.dh.product.repository.InventoryTransactionRepository;

/**
 * 재고 차감 멱등성 (Redmine posselect #211).
 *
 * <p>배경: order.api는 결제 확정 시 5초 타임아웃으로 재고 차감을 HTTP 호출한다. 차감이 여기서
 * 커밋된 뒤 응답만 유실되면 order.api는 자기 트랜잭션을 롤백해 주문을 CREATED로 되돌리고,
 * 사용자가 결제를 재시도하면 같은 주문으로 재고가 두 번 빠졌다.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceIdempotencyTest {

    private static final Long ORDER_ID = 42L;
    private static final Long VARIANT_ID = 7L;

    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private InventoryTransactionRepository inventoryTransactionRepository;
    @Mock
    private CacheManager cacheManager;
    @Mock
    private InventoryService self;

    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = new InventoryService(
                inventoryRepository, inventoryTransactionRepository, cacheManager, self);
    }

    private Inventory inventoryWith(int quantity) {
        Product product = new Product();
        product.setId(100L);
        ProductVariant variant = new ProductVariant();
        variant.setId(VARIANT_ID);
        variant.setProduct(product);
        return new Inventory(variant, quantity);
    }

    private List<DeductItem> oneItem(int quantity) {
        return List.of(new DeductItem(VARIANT_ID, quantity));
    }

    @Test
    @DisplayName("처음 들어온 주문은 정상 차감된다")
    void 처음_차감되는_주문() {
        Inventory inventory = inventoryWith(10);
        when(inventoryTransactionRepository.existsByOrderIdAndType(
                ORDER_ID, InventoryTransactionType.ORDER_DEDUCT)).thenReturn(false);
        when(inventoryRepository.findByVariantId(VARIANT_ID)).thenReturn(Optional.of(inventory));

        List<InventoryBalanceResponse> result = service.deductOnce(ORDER_ID, oneItem(3));

        assertThat(inventory.getQuantity()).isEqualTo(7);
        assertThat(result).singleElement()
                .extracting(InventoryBalanceResponse::remainingQuantity).isEqualTo(7);
        verify(inventoryTransactionRepository).save(any(InventoryTransaction.class));
    }

    @Test
    @DisplayName("이미 차감된 주문이 다시 오면 재차감하지 않고 현재 잔고만 반환한다")
    void 같은_주문의_재시도는_재차감하지_않는다() {
        Inventory inventory = inventoryWith(7);
        when(inventoryTransactionRepository.existsByOrderIdAndType(
                ORDER_ID, InventoryTransactionType.ORDER_DEDUCT)).thenReturn(true);
        when(inventoryRepository.findByVariantId(VARIANT_ID)).thenReturn(Optional.of(inventory));

        List<InventoryBalanceResponse> result = service.deductOnce(ORDER_ID, oneItem(3));

        // 재고가 그대로여야 한다 - 이게 깨지면 결제 재시도마다 재고가 사라진다.
        assertThat(inventory.getQuantity()).isEqualTo(7);
        assertThat(result).singleElement()
                .extracting(InventoryBalanceResponse::remainingQuantity).isEqualTo(7);
        verify(inventoryTransactionRepository, never()).save(any(InventoryTransaction.class));
    }

    @Test
    @DisplayName("동시 요청이 유니크 제약에 걸리면 실패가 아니라 현재 잔고 반환으로 처리한다")
    void 동시_중복_요청은_제약으로_막고_성공으로_취급한다() {
        List<DeductItem> items = oneItem(3);
        List<InventoryBalanceResponse> balances = List.of(new InventoryBalanceResponse(VARIANT_ID, 7));
        when(self.deductOnce(ORDER_ID, items))
                .thenThrow(new DataIntegrityViolationException("uq_inventory_transactions_order_deduct"));
        when(self.currentBalances(items)).thenReturn(balances);

        List<InventoryBalanceResponse> result = service.deductForOrder(ORDER_ID, items);

        assertThat(result).isEqualTo(balances);
        verify(self).currentBalances(items);
    }

    @Test
    @DisplayName("정상 경로에서는 트랜잭션 메서드를 그대로 위임한다")
    void 정상_경로는_그대로_위임한다() {
        List<DeductItem> items = oneItem(3);
        List<InventoryBalanceResponse> balances = List.of(new InventoryBalanceResponse(VARIANT_ID, 7));
        when(self.deductOnce(ORDER_ID, items)).thenReturn(balances);

        assertThat(service.deductForOrder(ORDER_ID, items)).isEqualTo(balances);
        verify(self, never()).currentBalances(any());
    }

    @Test
    @DisplayName("재고 부족은 멱등성 확인 이후에도 그대로 예외로 남는다")
    void 재고_부족은_여전히_실패한다() {
        Inventory inventory = inventoryWith(2);
        when(inventoryTransactionRepository.existsByOrderIdAndType(
                anyLong(), eq(InventoryTransactionType.ORDER_DEDUCT))).thenReturn(false);
        when(inventoryRepository.findByVariantId(VARIANT_ID)).thenReturn(Optional.of(inventory));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.deductOnce(ORDER_ID, oneItem(3)))
                .isInstanceOf(IllegalStateException.class);
    }
}
