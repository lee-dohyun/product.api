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
import com.dh.product.domain.Product;
import com.dh.product.dto.InventoryDtos.DeductItem;
import com.dh.product.dto.InventoryDtos.InventoryBalanceResponse;
import com.dh.product.repository.InventoryRepository;
import com.dh.product.repository.InventoryTransactionRepository;
import com.dh.product.repository.ProductRepository;

@Service
@Transactional(readOnly = true)
public class InventoryService {

    // ProductService.PRODUCT_CACHE와 동일한 캐시명 - 재고가 바뀌면 product:{id} 캐시(stockQuantity
    // 포함)도 같이 무효화해야 하는데, 순환 의존을 피하려고 ProductService를 참조하는 대신 CacheManager를
    // 직접 쓴다.
    private static final String PRODUCT_CACHE = "product";

    private final InventoryRepository inventoryRepository;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final ProductRepository productRepository;
    private final CacheManager cacheManager;

    public InventoryService(
            InventoryRepository inventoryRepository,
            InventoryTransactionRepository inventoryTransactionRepository,
            ProductRepository productRepository,
            CacheManager cacheManager) {
        this.inventoryRepository = inventoryRepository;
        this.inventoryTransactionRepository = inventoryTransactionRepository;
        this.productRepository = productRepository;
        this.cacheManager = cacheManager;
    }

    @Transactional
    public void initialize(Product product, int quantity) {
        Inventory inventory = new Inventory(product, quantity);
        inventoryRepository.save(inventory);
        record(inventory, InventoryTransactionType.RESTOCK, quantity, null, "상품 등록 초기 재고");
    }

    /** admin이 상품 수정 화면에서 재고 수량을 직접 바꿨을 때 - 델타를 이력에 남긴다. */
    @Transactional
    public void adjustTo(Product product, int newQuantity) {
        Inventory inventory = getOrCreate(product.getId(), product);
        int delta = newQuantity - inventory.getQuantity();
        if (delta == 0) {
            return;
        }
        inventory.setTo(newQuantity);
        syncProductStock(inventory);
        record(inventory, InventoryTransactionType.ADJUSTMENT, delta, null, "관리자 수동 조정");
    }

    /** 주문 결제 확정 시 order.api가 호출 - 항목 중 하나라도 재고 부족이면 전체 롤백된다. */
    @Transactional
    public List<InventoryBalanceResponse> deductForOrder(Long orderId, List<DeductItem> items) {
        return items.stream()
                .map(item -> {
                    Inventory inventory = getOrCreate(item.productId(), null);
                    inventory.deduct(item.quantity());
                    syncProductStock(inventory);
                    record(inventory, InventoryTransactionType.ORDER_DEDUCT, -item.quantity(), orderId, null);
                    return new InventoryBalanceResponse(item.productId(), inventory.getQuantity());
                })
                .toList();
    }

    private void syncProductStock(Inventory inventory) {
        inventory.getProduct().setStockQuantity(inventory.getQuantity());
        Cache cache = cacheManager.getCache(PRODUCT_CACHE);
        if (cache != null) {
            cache.evict(inventory.getProduct().getId());
        }
    }

    @Transactional
    public void deleteForProduct(Long productId) {
        inventoryRepository.findByProductId(productId).ifPresent(inventory -> {
            inventoryTransactionRepository.deleteByInventoryId(inventory.getId());
            inventoryRepository.delete(inventory);
        });
    }

    /**
     * inventories 테이블 도입 이전에 등록된 상품은 재고 행이 없다 - 없으면 그 상품의 현재
     * stockQuantity로 하나 만들어서 자연스럽게 채워 넣는다(별도 백필 마이그레이션 불필요).
     */
    private Inventory getOrCreate(Long productId, Product knownProduct) {
        return inventoryRepository.findByProductId(productId)
                .orElseGet(() -> {
                    Product product = knownProduct != null
                            ? knownProduct
                            : productRepository.findById(productId)
                                    .orElseThrow(() -> new NoSuchElementException("product not found: " + productId));
                    Inventory inventory = new Inventory(product, product.getStockQuantity());
                    inventoryRepository.save(inventory);
                    record(inventory, InventoryTransactionType.RESTOCK, product.getStockQuantity(), null,
                            "기존 상품 재고 데이터 자동 이관");
                    return inventory;
                });
    }

    private void record(Inventory inventory, InventoryTransactionType type, int change, Long orderId, String reason) {
        inventoryTransactionRepository.save(new InventoryTransaction(inventory, type, change, orderId, reason));
    }
}
