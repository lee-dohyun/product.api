-- =========================================================
-- 재고 차감 멱등성 + 음수 재고 방지 (Redmine posselect #211)
--
-- order.api가 결제 확정 시 HTTP로 재고 차감을 호출하는데, 차감이 여기서 커밋된 뒤
-- 응답이 유실되면(5초 타임아웃) order.api는 롤백하고 주문은 CREATED로 남는다.
-- 사용자가 결제를 재시도하면 같은 주문으로 재고가 두 번 빠졌다.
--
-- order_id는 원래부터 이력에 기록하고 있었지만 중복 판정에 쓰이지 않았다.
-- 애플리케이션에서 먼저 이력 존재를 확인하고, 동시 요청으로 그 확인을 둘 다
-- 통과하는 경우는 이 유니크 인덱스가 막는다. 응용 로직과 DB 제약 두 겹이다.
-- =========================================================
CREATE UNIQUE INDEX uq_inventory_transactions_order_deduct
    ON inventory_transactions (order_id, inventory_id)
    WHERE type = 'ORDER_DEDUCT';

-- 조회(주문별 차감 이력 확인)에도 쓰이므로 ORDER_RESTORE까지 포함한 인덱스를 따로 둔다.
CREATE INDEX idx_inventory_transactions_order_id
    ON inventory_transactions (order_id)
    WHERE order_id IS NOT NULL;

-- Inventory.deduct()가 애플리케이션에서 막고 있지만, 어떤 경로로든 재고가 음수가 되면
-- 그건 데이터 오류지 정상 상태가 아니다. 최후의 방어선을 DB에 둔다.
ALTER TABLE inventories
    ADD CONSTRAINT inventories_quantity_non_negative CHECK (quantity >= 0);
