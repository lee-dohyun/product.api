-- =========================================================
-- 주문 차감 이력에 "되돌려졌는가"를 둔다 (product.api#115)
--
-- 차감 멱등 판정이 "이 주문의 ORDER_DEDUCT 이력이 있는가"뿐이라, 보상 복원이 나간 주문을
-- 다시 결제하면 차감이 건너뛰어져 재고가 안 빠진 채 팔렸다. 복원도 차감 이력을 보지 않아
-- 차감된 적 없는 주문으로 부르면 재고가 늘었다.
--
-- 이제 기준은 "되돌려지지 않은 차감이 있는가"다.
--   차감: 되돌려지지 않은 차감이 있으면 건너뛴다(멱등). 없으면 뺀다.
--   복원: 되돌려지지 않은 차감을 reversed = true 로 바꾸는 데 성공한 요청만 재고를 더한다.
-- =========================================================
ALTER TABLE inventory_transactions
    ADD COLUMN reversed BOOLEAN NOT NULL DEFAULT FALSE;

-- 이미 복원 이력이 있는 주문의 차감은 되돌려진 것이다. 채우지 않으면 그 주문의 복원이 다시
-- 호출됐을 때(재시도) 한 번 더 더해진다.
UPDATE inventory_transactions d
   SET reversed = TRUE
 WHERE d.type = 'ORDER_DEDUCT'
   AND EXISTS (SELECT 1 FROM inventory_transactions r
                WHERE r.order_id = d.order_id AND r.type = 'ORDER_RESTORE');

-- 동시 중복 차감의 최종 방어(V3)를 "되돌려지지 않은 차감"으로 좁힌다. 좁히지 않으면 복원된
-- 주문의 재차감이 이 제약에 걸린다. 새 인덱스를 먼저 만들고 옛 인덱스를 지운다.
CREATE UNIQUE INDEX uq_inventory_transactions_order_active_deduct
    ON inventory_transactions (order_id, inventory_id)
    WHERE type = 'ORDER_DEDUCT' AND reversed = FALSE;

DROP INDEX uq_inventory_transactions_order_deduct;
