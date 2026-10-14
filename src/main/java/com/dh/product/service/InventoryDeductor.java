package com.dh.product.service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.product.domain.Inventory;
import com.dh.product.domain.InventoryTransaction;
import com.dh.product.domain.InventoryTransactionType;
import com.dh.product.dto.InventoryDtos.DeductItem;
import com.dh.product.dto.InventoryDtos.InventoryBalanceResponse;
import com.dh.product.dto.InventoryDtos.PartialRestoreItem;
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
    private final JdbcTemplate jdbc;

    public InventoryDeductor(
            InventoryRepository inventoryRepository,
            InventoryTransactionRepository inventoryTransactionRepository,
            CacheManager cacheManager,
            JdbcTemplate jdbc) {
        this.inventoryRepository = inventoryRepository;
        this.inventoryTransactionRepository = inventoryTransactionRepository;
        this.cacheManager = cacheManager;
        this.jdbc = jdbc;
    }

    /**
     * 같은 orderId로 몇 번을 호출해도 재고는 한 번만 뺀다 - 그 차감이 복원으로 되돌려지기 전까지.
     * 항목 중 하나라도 재고가 부족하면 전체가 롤백된다.
     *
     * <p>같은 주문의 차감 요청이 <i>동시에</i> 들어와 아래 이력 확인을 둘 다 통과하는 경우는
     * {@code uq_inventory_transactions_order_active_deduct} 유니크 인덱스(V24)가 뒤엣것을 막는다. 그 예외를
     * 성공으로 바꿔 주는 건 트랜잭션 바깥인 {@link InventoryDeductionService}의 몫이다.
     */
    @Transactional
    public List<InventoryBalanceResponse> deductOnce(Long orderId, List<DeductItem> items) {
        // 판정은 "되돌려지지 않은 차감이 있는가"다(product.api#115). 이력 존재만 보면, 결제 확정 실패로
        // 보상 복원이 나간 주문을 다시 결제할 때 차감이 건너뛰어져 재고가 안 빠진 채 팔린다.
        if (inventoryTransactionRepository.existsActiveDeduct(orderId)) {
            log.info("이미 차감된 주문 - 재차감 없이 현재 잔고를 반환 (orderId={})", orderId);
            return balancesOf(items);
        }
        // 행 잠금을 항상 variantId 순으로 잡는다. 요청 순서대로 잡으면 같은 두 상품을 반대 순서로 담은
        // 주문끼리 서로의 잠금을 기다리다 교착으로 한쪽이 죽는다. 응답은 요청 순서를 유지한다.
        InventoryBalanceResponse[] balances = new InventoryBalanceResponse[items.size()];
        IntStream.range(0, items.size()).boxed()
                .sorted(Comparator.comparing(i -> items.get(i).variantId()))
                .forEach(i -> balances[i] = deductAtomically(items.get(i), orderId));
        return List.of(balances);
    }

    /**
     * 서로 다른 주문이 같은 재고 행에 몰려도 순서대로 처리되게 조건부 UPDATE 로 뺀다(product.api#35).
     * 엔티티의 수량은 건드리지 않는다 - 건드리면 flush 때 버전 검사가 붙은 UPDATE 가 한 번 더 나간다.
     */
    private InventoryBalanceResponse deductAtomically(DeductItem item, Long orderId) {
        Inventory inventory = getOrThrow(item.variantId());
        if (inventoryRepository.deductIfEnough(inventory.getId(), item.quantity(), LocalDateTime.now()) == 0) {
            throw new IllegalStateException(
                    "재고가 부족합니다: variantId=" + item.variantId() + ", 요청=" + item.quantity());
        }
        int remaining = inventoryRepository.findQuantityById(inventory.getId());
        evictProductCache(inventory.getVariant().getProduct().getId());
        inventoryTransactionRepository.save(new InventoryTransaction(
                inventory, InventoryTransactionType.ORDER_DEDUCT, -item.quantity(), remaining, orderId, null));
        return new InventoryBalanceResponse(item.variantId(), remaining);
    }

    /**
     * 주문 전체 복원(결제 보상·전액 환불). 되돌릴 차감을 얻은 요청만 재고를 더한다(product.api#115).
     *
     * <p>더하는 수량은 요청이 아니라 <b>장부</b>에서 온다(gateway#320): 차감 수량에서 부분 복원으로 이미 되돌린
     * 수량을 뺀 만큼이다. order.api 는 주문 수량 그대로 보내므로 요청 수량을 믿으면, 일부 상품을 반품한 뒤
     * 전액 환불이 오는 순간 그 반품분이 한 번 더 더해진다. reversed 전환과 남은 수량 읽기를 UPDATE ... RETURNING
     * 한 문장으로 해서, 동시에 들어온 부분 복원과 엇갈려도 잠금이 풀린 뒤의 값을 본다.
     */
    @Transactional
    public List<InventoryBalanceResponse> restoreOnce(Long orderId, List<com.dh.product.dto.InventoryDtos.RestoreItem> items) {
        Map<Long, Integer> remainingByInventory = new HashMap<>();
        jdbc.query("""
                UPDATE inventory_transactions
                   SET reversed = TRUE
                 WHERE order_id = ? AND type = 'ORDER_DEDUCT' AND reversed = FALSE
                RETURNING inventory_id, (-quantity_change - restored_quantity) AS remaining
                """, rs -> {
            remainingByInventory.merge(rs.getLong("inventory_id"), rs.getInt("remaining"), Integer::sum);
        }, orderId);
        // 0 행이면 이미 복원됐거나 차감된 적이 없는 주문이다 - 후자를 그냥 더하면 없던 재고가 생긴다.
        if (remainingByInventory.isEmpty()) {
            log.info("되돌릴 차감이 없는 주문 - 복원 없이 현재 잔고를 반환 (orderId={})", orderId);
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
                    int amount = remainingByInventory.getOrDefault(inventory.getId(), 0);
                    if (amount > 0) {
                        inventory.restore(amount);
                        evictProductCache(inventory.getVariant().getProduct().getId());
                        inventoryTransactionRepository.save(new InventoryTransaction(
                                inventory, InventoryTransactionType.ORDER_RESTORE, amount, orderId, null));
                    }
                    return new InventoryBalanceResponse(item.variantId(), inventory.getQuantity());
                })
                .toList();
    }

    /**
     * 상품별 부분 복원(gateway#320) - 고객이 주문의 일부 상품만 취소·반품했을 때 그 수량만 되돌린다.
     *
     * <p>멱등 키는 {@code claimKey}(order.api 의 클레임)다. 같은 키로 이미 되돌린 재고는 건너뛰고, 동시에 두 번 들어와
     * 존재 확인을 둘 다 통과한 경우는 V25 유니크 인덱스가 뒤엣것을 막는다(그 예외를 성공으로 바꾸는 건
     * {@link InventoryDeductionService}). 상한은 조건부 UPDATE 한 문장으로 판정한다 — 되돌리지 않은 차감 행에서
     * "이미 되돌린 수량 + 이번 수량 ≤ 뺀 수량"일 때만 갱신되고, 아니면 0 행이라 거부한다.
     */
    @Transactional
    public List<InventoryBalanceResponse> restorePartialOnce(Long orderId, String claimKey,
            List<PartialRestoreItem> items) {
        InventoryBalanceResponse[] balances = new InventoryBalanceResponse[items.size()];
        // 차감과 같은 이유로 잠금 순서를 variantId 순으로 고정한다(교착 방지). 응답은 요청 순서.
        IntStream.range(0, items.size()).boxed()
                .sorted(Comparator.comparing(i -> items.get(i).variantId()))
                .forEach(i -> balances[i] = restorePartialLine(orderId, claimKey, items.get(i)));
        return List.of(balances);
    }

    private InventoryBalanceResponse restorePartialLine(Long orderId, String claimKey, PartialRestoreItem item) {
        Inventory inventory = getOrThrow(item.variantId());
        Integer already = jdbc.queryForObject("""
                SELECT count(*) FROM inventory_transactions
                 WHERE claim_key = ? AND inventory_id = ? AND type = 'ORDER_RESTORE'
                """, Integer.class, claimKey, inventory.getId());
        if (already != null && already > 0) {
            log.info("이미 처리한 부분 복원 - 재고를 더하지 않는다 (orderId={}, claimKey={})", orderId, claimKey);
            return new InventoryBalanceResponse(item.variantId(), inventoryRepository.findQuantityById(inventory.getId()));
        }
        int updated = jdbc.update("""
                UPDATE inventory_transactions
                   SET restored_quantity = restored_quantity + ?
                 WHERE id = (SELECT id FROM inventory_transactions
                              WHERE order_id = ? AND inventory_id = ? AND type = 'ORDER_DEDUCT' AND reversed = FALSE
                              ORDER BY id DESC LIMIT 1)
                   AND restored_quantity + ? <= -quantity_change
                """, item.quantity(), orderId, inventory.getId(), item.quantity());
        if (updated == 0) {
            throw new PartialRestoreRejectedException("되돌릴 수 있는 차감 수량을 넘었거나 차감이 없습니다: orderId="
                    + orderId + ", variantId=" + item.variantId() + ", 요청=" + item.quantity());
        }
        inventoryRepository.addQuantity(inventory.getId(), item.quantity(), LocalDateTime.now());
        int remaining = inventoryRepository.findQuantityById(inventory.getId());
        evictProductCache(inventory.getVariant().getProduct().getId());
        InventoryTransaction restore = new InventoryTransaction(
                inventory, InventoryTransactionType.ORDER_RESTORE, item.quantity(), remaining, orderId, null);
        restore.setClaimKey(claimKey);
        inventoryTransactionRepository.save(restore);
        return new InventoryBalanceResponse(item.variantId(), remaining);
    }

    /**
     * 교환 발송분 차감(gateway#320). 주문 차감과 같은 원자적 조건부 UPDATE 를 쓰되, 멱등은 클레임 키로 판정한다 —
     * 같은 키로 이미 뺀 재고는 건너뛰고, 동시에 두 번 들어온 요청은 V25 유니크 인덱스가 막는다. 하나라도 재고가
     * 부족하면 전체가 롤백된다.
     */
    @Transactional
    public List<InventoryBalanceResponse> deductForClaimOnce(Long orderId, String claimKey, List<DeductItem> items) {
        InventoryBalanceResponse[] balances = new InventoryBalanceResponse[items.size()];
        IntStream.range(0, items.size()).boxed()
                .sorted(Comparator.comparing(i -> items.get(i).variantId()))
                .forEach(i -> {
                    DeductItem item = items.get(i);
                    Inventory inventory = getOrThrow(item.variantId());
                    Integer already = jdbc.queryForObject("""
                            SELECT count(*) FROM inventory_transactions
                             WHERE claim_key = ? AND inventory_id = ? AND type = 'EXCHANGE_DEDUCT'
                            """, Integer.class, claimKey, inventory.getId());
                    if (already != null && already > 0) {
                        balances[i] = new InventoryBalanceResponse(item.variantId(),
                                inventoryRepository.findQuantityById(inventory.getId()));
                        return;
                    }
                    if (inventoryRepository.deductIfEnough(inventory.getId(), item.quantity(), LocalDateTime.now()) == 0) {
                        throw new IllegalStateException(
                                "재고가 부족합니다: variantId=" + item.variantId() + ", 요청=" + item.quantity());
                    }
                    int remaining = inventoryRepository.findQuantityById(inventory.getId());
                    evictProductCache(inventory.getVariant().getProduct().getId());
                    InventoryTransaction tx = new InventoryTransaction(
                            inventory, InventoryTransactionType.EXCHANGE_DEDUCT, -item.quantity(), remaining, orderId, null);
                    tx.setClaimKey(claimKey);
                    inventoryTransactionRepository.save(tx);
                    balances[i] = new InventoryBalanceResponse(item.variantId(), remaining);
                });
        return List.of(balances);
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
}
