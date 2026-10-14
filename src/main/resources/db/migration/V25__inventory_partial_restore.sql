-- =========================================================
-- 상품별 부분 복원 (gateway#320)
--
-- 고객이 주문의 일부 상품만 취소·반품하면 그 수량만 재고에 되돌려야 한다. 지금 장부는 차감 1행에
-- "되돌려졌는가"(reversed, V24) 하나뿐이라 수량 일부를 되돌릴 수 없다.
--
--   restored_quantity : ORDER_DEDUCT 행 전용. 부분 복원으로 이미 되돌린 수량. 차감 수량(-quantity_change)을
--                       넘을 수 없다. 주문 전체 복원(reversed=true 전환)은 "차감 수량 - 이 값"만 더한다.
--   claim_key         : ORDER_RESTORE 행 중 부분 복원에만 채운다. order.api 의 클레임 식별자로, 같은 키의
--                       재시도가 두 번 더해지지 않게 하는 멱등 키다.
-- =========================================================
ALTER TABLE inventory_transactions
    ADD COLUMN restored_quantity INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN claim_key VARCHAR(64);

ALTER TABLE inventory_transactions
    ADD CONSTRAINT inventory_transactions_restored_quantity_range
        CHECK (restored_quantity >= 0 AND (type <> 'ORDER_DEDUCT' OR restored_quantity <= -quantity_change));

-- 같은 클레임이 같은 재고를 두 번 되돌리지 못하게 막는 최종 방어선. 애플리케이션의 존재 확인을 동시에
-- 통과한 재시도는 여기서 걸린다.
CREATE UNIQUE INDEX uq_inventory_transactions_claim_restore
    ON inventory_transactions (claim_key, inventory_id)
    WHERE claim_key IS NOT NULL;
