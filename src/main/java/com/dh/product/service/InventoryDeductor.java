package com.dh.product.service;

import java.util.List;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.product.domain.Inventory;
import com.dh.product.domain.InventoryTransaction;
import com.dh.product.domain.InventoryTransactionType;
import com.dh.product.dto.InventoryDtos.DeductItem;
import com.dh.product.dto.InventoryDtos.InventoryBalanceResponse;
import com.dh.product.repository.InventoryRepository;
import com.dh.product.repository.InventoryTransactionRepository;

/**
 * 주문 재고 차감의 트랜잭션 경계 (Redmine posselect #211).
 *
 * <p><b>이 클래스에는 클래스 레벨 {@code @Transactional(readOnly = true)}를 붙이지 말 것.</b>
 * 1차 시도(dac4437)는 이 로직을 {@link InventoryService}(클래스 레벨 readOnly=true) 안에 두고
 * 전파 속성 + {@code @Lazy} 자기 프록시로 우회했는데, 운영에서 <b>"이력 INSERT는 되는데 차감
 * UPDATE만 조용히 사라지는"</b> 회귀가 나 롤백했다. 그 정확한 메커니즘은 끝내 재현되지 않았다 —
 * 읽기 전용 트랜잭션도, 트랜잭션 없음도 지금 스택에서는 예외를 던지며 시끄럽게 실패한다
 * ({@link InventoryDeductionIntegrationTest}로 확인).
 *
 * <p>그래서 원인 규명 대신 <b>우회 자체를 없앴다</b>: 쓰기 경로를 읽기 전용 기본값도 자기
 * 프록시도 없는 별도 빈으로 분리했다 (~/msa/AGENTS.md §3 트랜잭션 규칙).
 */
@Service
public class InventoryDeductor {

    private static final Logger log = LoggerFactory.getLogger(InventoryDeductor.class);

    // ProductService.PRODUCT_CACHE와 동일한 캐시명 - 재고가 바뀌면 product:{id} 캐시(계산된
    // price/stockQuantity 포함)도 같이 무효화해야 하는데, 순환 의존을 피하려고 ProductService를
    // 참조하는 대신 CacheManager를 직접 쓴다.
    private static final String PRODUCT_CACHE = "product";

    private final InventoryRepository inventoryRepository;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final CacheManager cacheManager;

    public InventoryDeductor(
            InventoryRepository inventoryRepository,
            InventoryTransactionRepository inventoryTransactionRepository,
            CacheManager cacheManager) {
        this.inventoryRepository = inventoryRepository;
        this.inventoryTransactionRepository = inventoryTransactionRepository;
        this.cacheManager = cacheManager;
    }

    /**
     * 같은 orderId로 몇 번을 호출해도 재고는 한 번만 뺀다. 항목 중 하나라도 재고가 부족하면
     * 전체가 롤백된다.
     *
     * <p>같은 주문의 차감 요청이 <i>동시에</i> 들어와 아래 이력 확인을 둘 다 통과하는 경우는
     * {@code uq_inventory_transactions_order_deduct} 유니크 인덱스가 뒤엣것을 막는다. 그 예외를
     * 성공으로 바꿔 주는 건 트랜잭션 바깥인 {@link InventoryDeductionService}의 몫이다.
     */
    @Transactional
    public List<InventoryBalanceResponse> deductOnce(Long orderId, List<DeductItem> items) {
        if (inventoryTransactionRepository.existsByOrderIdAndType(
                orderId, InventoryTransactionType.ORDER_DEDUCT)) {
            log.info("이미 차감된 주문 - 재차감 없이 현재 잔고를 반환 (orderId={})", orderId);
            return balancesOf(items);
        }
        return items.stream()
                .map(item -> {
                    Inventory inventory = getOrThrow(item.variantId());
                    inventory.deduct(item.quantity());
                    evictProductCache(inventory.getVariant().getProduct().getId());
                    record(inventory, item.quantity(), orderId);
                    return new InventoryBalanceResponse(item.variantId(), inventory.getQuantity());
                })
                .toList();
    }

    @Transactional
    public List<InventoryBalanceResponse> restoreOnce(Long orderId, List<com.dh.product.dto.InventoryDtos.RestoreItem> items) {
        if (inventoryTransactionRepository.existsByOrderIdAndType(
                orderId, InventoryTransactionType.ORDER_RESTORE)) {
            log.info("이미 복원된 주문 - 중복 복원 없이 현재 잔고를 반환 (orderId={})", orderId);
            return items.stream()
                    .map(item -> new InventoryBalanceResponse(
                            item.variantId(),
                            inventoryRepository.findByVariantId(item.variantId())
                                    .map(Inventory::getQuantity)
                                    .orElse(0)))
                    .toList();
        }
        return items.stream()
                .map(item -> {
                    Inventory inventory = getOrThrow(item.variantId());
                    inventory.restore(item.quantity());
                    evictProductCache(inventory.getVariant().getProduct().getId());
                    inventoryTransactionRepository.save(new InventoryTransaction(
                            inventory, InventoryTransactionType.ORDER_RESTORE, item.quantity(), orderId, null));
                    return new InventoryBalanceResponse(item.variantId(), inventory.getQuantity());
                })
                .toList();
    }

    /** 차감 없이 요청 항목들의 현재 잔고만 조회한다 - 이미 처리된 주문에 응답할 때 쓴다. */
    @Transactional(readOnly = true)
    public List<InventoryBalanceResponse> currentBalances(List<DeductItem> items) {
        return balancesOf(items);
    }

    @Transactional(readOnly = true)
    public List<InventoryBalanceResponse> currentBalancesForRestore(List<com.dh.product.dto.InventoryDtos.RestoreItem> items) {
        return items.stream()
                .map(item -> new InventoryBalanceResponse(
                        item.variantId(),
                        inventoryRepository.findByVariantId(item.variantId())
                                .map(Inventory::getQuantity)
                                .orElse(0)))
                .toList();
    }

    private List<InventoryBalanceResponse> balancesOf(List<DeductItem> items) {
        return items.stream()
                .map(item -> new InventoryBalanceResponse(
                        item.variantId(),
                        inventoryRepository.findByVariantId(item.variantId())
                                .map(Inventory::getQuantity)
                                .orElse(0)))
                .toList();
    }

    private void evictProductCache(Long productId) {
        Cache cache = cacheManager.getCache(PRODUCT_CACHE);
        if (cache != null) {
            cache.evict(productId);
        }
    }

    private Inventory getOrThrow(Long variantId) {
        return inventoryRepository.findByVariantId(variantId)
                .orElseThrow(() -> new NoSuchElementException("inventory not found for variant: " + variantId));
    }

    private void record(Inventory inventory, int deducted, Long orderId) {
        inventoryTransactionRepository.save(new InventoryTransaction(
                inventory, InventoryTransactionType.ORDER_DEDUCT, -deducted, orderId, null));
    }
}
