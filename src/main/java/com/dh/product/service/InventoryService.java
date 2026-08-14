package com.dh.product.service;

import java.util.List;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    // ProductService.PRODUCT_CACHE와 동일한 캐시명 - 재고가 바뀌면 product:{id} 캐시(계산된
    // price/stockQuantity 포함)도 같이 무효화해야 하는데, 순환 의존을 피하려고 ProductService를
    // 참조하는 대신 CacheManager를 직접 쓴다.
    private static final String PRODUCT_CACHE = "product";

    private final InventoryRepository inventoryRepository;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final CacheManager cacheManager;
    /**
     * 자기 자신의 프록시. @Transactional은 프록시를 거쳐야 적용되므로 같은 클래스 안에서
     * this로 부르면 트랜잭션 경계가 생기지 않는다 — 트랜잭션을 여기서 시작하고 예외는 그
     * 바깥에서 잡아야 해서 이 우회가 필요하다. 순환 참조라 @Lazy가 필수.
     */
    private final InventoryService self;

    public InventoryService(
            InventoryRepository inventoryRepository,
            InventoryTransactionRepository inventoryTransactionRepository,
            CacheManager cacheManager,
            @Lazy InventoryService self) {
        this.inventoryRepository = inventoryRepository;
        this.inventoryTransactionRepository = inventoryTransactionRepository;
        this.cacheManager = cacheManager;
        this.self = self;
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

    /**
     * 주문 결제 확정 시 order.api가 호출한다. 항목 중 하나라도 재고 부족이면 전체 롤백된다.
     *
     * <p><b>같은 orderId로 몇 번을 호출해도 재고는 한 번만 빠진다.</b> order.api가 이 호출의
     * 응답을 받지 못하면(5초 타임아웃) 자기 트랜잭션을 롤백해 주문을 CREATED로 되돌리는데,
     * 이때 차감은 이미 여기서 커밋돼 있을 수 있다. 사용자가 결제를 재시도하면 같은 주문으로
     * 재고가 두 번 빠지던 것이 #211이다.
     *
     * <p>이 메서드는 트랜잭션 <b>바깥</b>에서 돌아야 한다 — 동시 요청이 이력 확인을 둘 다
     * 통과해 유니크 제약에 걸리는 경우, 예외를 잡는 지점이 실패한 트랜잭션 밖이어야 하기
     * 때문이다. 안에서 잡으면 이미 rollback-only로 표시돼 있어 아무것도 되돌릴 수 없다.
     *
     * <p>NOT_SUPPORTED가 반드시 필요하다. 이 클래스에는 {@code @Transactional(readOnly = true)}가
     * 걸려 있어서 그냥 두면 이 메서드가 읽기 전용 트랜잭션을 열고, {@code self.deductOnce()}가
     * 새 트랜잭션을 여는 대신 거기에 합류해 버린다. 그러면 Hibernate가 flush를 하지 않아
     * <b>차감이 조용히 사라진다</b>.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<InventoryBalanceResponse> deductForOrder(Long orderId, List<DeductItem> items) {
        try {
            return self.deductOnce(orderId, items);
        } catch (DataIntegrityViolationException e) {
            // 같은 주문의 차감이 동시에 두 번 들어와 유니크 인덱스가 뒤엣것을 막은 경우.
            // 앞엣것이 이미 정확히 같은 일을 했으므로 실패가 아니라 성공으로 취급한다.
            log.info("동시 중복 차감 요청을 제약으로 차단 (orderId={})", orderId);
            return self.currentBalances(items);
        }
    }

    @Transactional
    public List<InventoryBalanceResponse> deductOnce(Long orderId, List<DeductItem> items) {
        if (inventoryTransactionRepository.existsByOrderIdAndType(
                orderId, InventoryTransactionType.ORDER_DEDUCT)) {
            log.info("이미 차감된 주문 - 재차감 없이 현재 잔고를 반환 (orderId={})", orderId);
            return currentBalances(items);
        }
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

    /** 차감 없이 요청 항목들의 현재 잔고만 조회한다 - 이미 처리된 주문에 응답할 때 쓴다. */
    @Transactional(readOnly = true)
    public List<InventoryBalanceResponse> currentBalances(List<DeductItem> items) {
        return items.stream()
                .map(item -> new InventoryBalanceResponse(item.variantId(), getQuantity(item.variantId())))
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
