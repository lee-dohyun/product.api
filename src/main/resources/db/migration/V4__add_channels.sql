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

-- 기존 이름 기반 전역 Unique 제약을 (채널, 부모, 이름) 복합 Unique로 완화
ALTER TABLE categories DROP CONSTRAINT uq_categories_name;
ALTER TABLE categories ADD CONSTRAINT uq_categories_channel_parent_name UNIQUE NULLS NOT DISTINCT (channel_id, parent_id, name);
