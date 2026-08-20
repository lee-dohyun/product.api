-- 채널 테이블 신설
CREATE TABLE channels (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    domain     VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 기본 채널(종합몰) 1건 삽입
INSERT INTO channels (id, name, domain) VALUES (1, '종합몰', 'posselect.com');

-- 카테고리 테이블에 채널 ID 추가 및 제약 변경
ALTER TABLE categories ADD COLUMN channel_id BIGINT;
UPDATE categories SET channel_id = 1;
ALTER TABLE categories ALTER COLUMN channel_id SET NOT NULL;
ALTER TABLE categories ADD CONSTRAINT fk_categories_channel FOREIGN KEY (channel_id) REFERENCES channels(id);

-- 기존 이름 기반 전역 Unique 제약을 (채널, 부모, 이름) 복합 Unique로 완화.
--
-- 제약 이름을 하드코딩하지 않고 동적으로 찾아서 지운다: 이 DB가 V1__baseline.sql로 처음부터
-- 만들어졌다면 이름이 uq_categories_name이지만, 운영 DB처럼 Flyway baseline-on-migrate로
-- V1이 스킵되고 실제로는 예전 Hibernate ddl-auto:update가 만든 스키마라면 제약 이름이
-- Hibernate 해시 이름(예: ukt8o6pivur7nn124jehx7cygw5)이라 하드코딩된 이름으로는 못 지운다.
-- 실제로 운영 DB에서 이 문제로 배포가 막혔다(2026-08-20).
DO $$
DECLARE
    constraint_name text;
BEGIN
    SELECT conname INTO constraint_name
    FROM pg_constraint
    WHERE conrelid = 'categories'::regclass
      AND contype = 'u'
      AND conkey = (
          SELECT array_agg(attnum ORDER BY attnum)
          FROM pg_attribute
          WHERE attrelid = 'categories'::regclass AND attname = 'name'
      );

    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE categories DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;

ALTER TABLE categories ADD CONSTRAINT uq_categories_channel_parent_name UNIQUE NULLS NOT DISTINCT (channel_id, parent_id, name);
