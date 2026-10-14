-- =========================================================
-- 교환 발송분 차감 유형을 거래 유형 CHECK 에 넣는다 (gateway#320)
--
-- 운영 catalogdb 는 Hibernate 가 만든 스키마 위에 Flyway 기준선(V1)을 얹어서, 마이그레이션 파일에 없는
-- enum CHECK(inventory_transactions_type_check)가 운영에만 있었다. 테스트 DB(마이그레이션으로 만든다)에는
-- 이 제약이 없어 EXCHANGE_DEDUCT 추가(V25 이후)가 테스트는 통과하고 운영에서만 INSERT 가 막혔다
-- (2026-10-14 운영 교환 실측에서 발견).
--
-- IF EXISTS 로 지우고 다시 만든다 — 운영에서는 값 목록을 넓히고, 테스트 DB 에도 같은 제약이 생겨 앞으로는
-- 새 유형을 빼먹으면 테스트에서 먼저 막힌다.
-- =========================================================
ALTER TABLE inventory_transactions DROP CONSTRAINT IF EXISTS inventory_transactions_type_check;
ALTER TABLE inventory_transactions ADD CONSTRAINT inventory_transactions_type_check
    CHECK (type IN ('RESTOCK', 'ADJUSTMENT', 'ORDER_DEDUCT', 'ORDER_RESTORE', 'EXCHANGE_DEDUCT'));
