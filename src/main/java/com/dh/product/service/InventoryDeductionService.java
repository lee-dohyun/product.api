package com.dh.product.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.dh.product.dto.InventoryDtos.DeductItem;
import com.dh.product.dto.InventoryDtos.InventoryBalanceResponse;

/**
 * 주문 결제 확정 시 order.api가 호출하는 재고 차감의 진입점 (Redmine posselect #211).
 *
 * <p>order.api는 자기 트랜잭션 안에서 5초 타임아웃으로 이 API를 호출한다. 차감이 여기서 커밋된
 * 뒤 응답만 유실되면 order.api는 롤백해 주문을 CREATED로 되돌리고, 사용자가 결제를 재시도하면
 * 같은 주문으로 재고가 두 번 빠지던 것이 #211이다. 방어는 두 겹이다 — 응용 레벨의 이력 확인
 * ({@link InventoryDeductor#deductOnce})과 DB 레벨의 부분 유니크 인덱스(V3).
 */
@Service
public class InventoryDeductionService {

    private static final Logger log = LoggerFactory.getLogger(InventoryDeductionService.class);

    private final InventoryDeductor deductor;

    public InventoryDeductionService(InventoryDeductor deductor) {
        this.deductor = deductor;
    }

    /**
     * <b>이 메서드에는 {@code @Transactional}을 붙이지 말 것 — 이 클래스에 클래스 레벨 선언이
     * 없는 것도 의도적이다.</b> 아래 catch는 실패한 트랜잭션 <i>바깥</i>이어야 의미가 있다.
     * 트랜잭션 안에서 잡으면 이미 rollback-only로 표시된 뒤라 이어지는 잔고 조회까지 같이 죽는다.
     * 트랜잭션 경계는 {@link InventoryDeductor}에만 있다.
     */
    public List<InventoryBalanceResponse> deductForOrder(Long orderId, List<DeductItem> items) {
        try {
            return deductor.deductOnce(orderId, items);
        } catch (DataIntegrityViolationException e) {
            // 같은 주문의 차감이 동시에 두 번 들어와 유니크 인덱스가 뒤엣것을 막은 경우.
            // 앞엣것이 이미 정확히 같은 일을 했으므로 실패가 아니라 성공으로 취급한다.
            log.info("동시 중복 차감 요청을 유니크 제약으로 차단 (orderId={})", orderId);
            return deductor.currentBalances(items);
        }
    }

    public List<InventoryBalanceResponse> restoreForOrder(Long orderId, List<com.dh.product.dto.InventoryDtos.RestoreItem> items) {
        try {
            return deductor.restoreOnce(orderId, items);
        } catch (DataIntegrityViolationException e) {
            log.info("동시 중복 복원 요청을 유니크 제약으로 차단 (orderId={})", orderId);
            return deductor.currentBalancesForRestore(items);
        }
    }
}
