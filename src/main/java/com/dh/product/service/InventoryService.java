package com.dh.product.service;

import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.product.domain.Inventory;
import com.dh.product.domain.InventoryTransaction;
import com.dh.product.domain.InventoryTransactionType;
import com.dh.product.domain.ProductVariant;
import com.dh.product.dto.InventoryDtos.DeductItem;
import com.dh.product.dto.InventoryDtos.InventoryBalanceResponse;
import com.dh.product.repository.InventoryRepository;
import com.dh.product.repository.InventoryTransactionRepository;

@Service
@Transactional(readOnly = true)
public class InventoryService {

    // ProductService.PRODUCT_CACHE와 동일한 캐시명 - 재고가 바뀌면 product:{id} 캐시(계산된
    // price/stockQuantity 포함)도 같이 무효화해야 하는데, 순환 의존을 피하려고 ProductService를
    // 참조하는 대신 CacheManager를 직접 쓴다.
    private static final String PRODUCT_CACHE = "product";

    private final InventoryRepository inventoryRepository;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final CacheManager cacheManager;

    public InventoryService(
            InventoryRepository inventoryRepository,
            InventoryTransactionRepository inventoryTransactionRepository,
            CacheManager cacheManager) {
        this.inventoryRepository = inventoryRepository;
        this.inventoryTransactionRepository = inventoryTransactionRepository;
        this.cacheManager = cacheManager;
    }

    /** variant 생성 시 재고 행을 함께 만든다 - 이 경로를 거치지 않고 만들어지는 variant는 없다. */
    @Transactional
    public void initialize(ProductVariant variant, int quantity) {
        Inventory inventory = new Inventory(variant, quantity);
        inventoryRepository.save(inventory);
        record(inventory, InventoryTransactionType.RESTOCK, quantity, null, "variant 등록 초기 재고");
    }

    /** admin이 재고 수량을 직접 바꿨을 때 - 델타를 이력에 남긴다. */
    @Transactional
    public void adjustTo(ProductVariant variant, int newQuantity) {
        Inventory inventory = getOrThrow(variant.getId());
        int delta = newQuantity - inventory.getQuantity();
        if (delta == 0) {
            return;
        }
        inventory.setTo(newQuantity);
        evictProductCache(variant.getProduct().getId());
        record(inventory, InventoryTransactionType.ADJUSTMENT, delta, null, "관리자 수동 조정");
    }

    /** 주문 결제 확정 시 order.api가 호출 - 항목 중 하나라도 재고 부족이면 전체 롤백된다. */
    @Transactional
    public List<InventoryBalanceResponse> deductForOrder(Long orderId, List<DeductItem> items) {
        return items.stream()
                .map(item -> {
                    Inventory inventory = getOrThrow(item.variantId());
                    inventory.deduct(item.quantity());
                    evictProductCache(inventory.getVariant().getProduct().getId());
                    record(inventory, InventoryTransactionType.ORDER_DEDUCT, -item.quantity(), orderId, null);
                    return new InventoryBalanceResponse(item.variantId(), inventory.getQuantity());
                })
                .toList();
    }

    @Transactional
    public void deleteForVariant(Long variantId) {
        inventoryRepository.findByVariantId(variantId).ifPresent(inventory -> {
            inventoryTransactionRepository.deleteByInventoryId(inventory.getId());
            inventoryRepository.delete(inventory);
        });
    }

    public Integer getQuantity(Long variantId) {
        return inventoryRepository.findByVariantId(variantId)
                .map(Inventory::getQuantity)
                .orElse(0);
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

    private void record(Inventory inventory, InventoryTransactionType type, int change, Long orderId, String reason) {
        inventoryTransactionRepository.save(new InventoryTransaction(inventory, type, change, orderId, reason));
    }
}
