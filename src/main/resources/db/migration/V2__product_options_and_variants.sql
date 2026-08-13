-- =========================================================
-- 상품 옵션(색상/사이즈 등) + SKU(variant) 도입
-- 재고(inventories)는 지금까지 상품(product_id) 단위였는데, 이제부터는 SKU(variant_id)
-- 단위로 추적한다 - 옵션이 없는 기존 상품도 "옵션 없는 variant 1개"로 취급해 동일한 경로를
-- 타게 만든다(특수 케이스 분기 없음).
-- 하위 엔티티(옵션값/variant/재고/이력) FK는 전부 ON DELETE CASCADE - 상품 삭제 시
-- 애플리케이션이 삭제 순서를 일일이 조율하지 않아도 DB가 정리한다.
-- =========================================================
CREATE TABLE product_options (
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    name        VARCHAR(50) NOT NULL,
    sort_order  SMALLINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_product_options_product_id ON product_options(product_id);

CREATE TABLE product_option_values (
    id          BIGSERIAL PRIMARY KEY,
    option_id   BIGINT NOT NULL REFERENCES product_options(id) ON DELETE CASCADE,
    value       VARCHAR(50) NOT NULL,
    sort_order  SMALLINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_product_option_values_option_id ON product_option_values(option_id);

CREATE TABLE product_variants (
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    sku         VARCHAR(50) NULL,
    price       NUMERIC(12,2) NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    CONSTRAINT uq_product_variants_sku UNIQUE (sku)
);

CREATE INDEX idx_product_variants_product_id ON product_variants(product_id);

-- variant를 정의하는 옵션값 조합 (옵션 1개당 값 1개 - 예: 색상=블랙 + 사이즈=L)
CREATE TABLE product_variant_option_values (
    variant_id       BIGINT NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    option_value_id  BIGINT NOT NULL REFERENCES product_option_values(id) ON DELETE CASCADE,
    PRIMARY KEY (variant_id, option_value_id)
);

-- 기존 상품마다 옵션 없는 기본 variant를 1개씩 만들고 현재 가격을 그대로 이관
INSERT INTO product_variants (product_id, sku, price, active, created_at, updated_at)
SELECT id, NULL, price, TRUE, now(), now()
FROM products;

-- 재고를 product_id 대신 variant_id에 연결
ALTER TABLE inventories ADD COLUMN variant_id BIGINT;

UPDATE inventories i
SET variant_id = pv.id
FROM product_variants pv
WHERE pv.product_id = i.product_id;

ALTER TABLE inventories ALTER COLUMN variant_id SET NOT NULL;
ALTER TABLE inventories ADD CONSTRAINT uq_inventories_variant_id UNIQUE (variant_id);
ALTER TABLE inventories
    ADD CONSTRAINT fk_inventories_variant FOREIGN KEY (variant_id)
    REFERENCES product_variants(id) ON DELETE CASCADE;

-- product_id 컬럼 및 거기 딸려있던 unique/FK 제약을 정리 (Postgres가 단일 컬럼 제약은
-- 자동으로 같이 드롭해준다)
ALTER TABLE inventories DROP COLUMN product_id;

-- inventory_transactions의 기존 FK는 Hibernate ddl-auto가 예전에 자동 생성한 거라
-- 실제 제약 이름을 신뢰할 수 없어(추측해서 DROP CONSTRAINT 하지 않음) - CASCADE로 바꾸는
-- 대신 InventoryService.deleteForVariant()가 이력을 먼저 지우고 재고를 지우는 순서로
-- 애플리케이션 레벨에서 정리한다.

-- 가격/재고는 이제 variant별로만 존재한다. products.price/stock_quantity는 variant 이관에
-- 쓰고 나면 더 이상 진실의 소스가 아니므로 제거 - ProductResponse는 활성 variant로부터
-- 매번 계산해서 내려준다(최저가/합계).
ALTER TABLE products DROP COLUMN price;
ALTER TABLE products DROP COLUMN stock_quantity;
