package com.dh.product.service;

import java.util.NoSuchElementException;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.product.domain.Inventory;
import com.dh.product.domain.InventoryTransaction;
import com.dh.product.domain.InventoryTransactionType;
import com.dh.product.domain.ProductVariant;
import com.dh.product.repository.InventoryRepository;
import com.dh.product.repository.InventoryTransactionRepository;

/**
 * 재고의 관리자/카탈로그 쪽 경로. 주문 차감은 여기 있지 않다 — 이 클래스에 차감을 넣었다가
 * 쓰기가 조용히 사라지는 회귀를 내고 롤백해서 {@link InventoryDeductor}로 분리했다
 * (Redmine posselect #211).
 *
 * <p>{@code initialize}/{@code adjustTo}가 여기 남은 건, 호출자인 {@code ProductService}가
 * 이미 쓰기 트랜잭션을 열어 둔 상태에서 합류하기 때문이다. <b>새 쓰기 경로를 이 클래스에
 * 추가하지 말 것</b> — 클래스 레벨 {@code readOnly = true}가 전파 함정의 입구다.
 */
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

    @Transactional
    public void deleteForVariant(Long variantId) {
        inventoryRepository.findByVariantId(variantId).ifPresent(inventory -> {
            inventoryTransactionRepository.deleteByInventoryId(inventory.getId());
            inventoryRepository.delete(inventory);
        });
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
