-- =========================================================
-- 베이스라인: Flyway 도입 이전(ddl-auto:update)에 이미 존재하던 스키마를 그대로 기록.
-- 운영 DB는 baseline-on-migrate로 이 버전을 "이미 적용됨"으로 표시하고 건너뛰므로,
-- 이 파일은 신규/로컬 DB를 처음부터 만들 때만 실제로 실행된다.
-- =========================================================
CREATE TABLE categories (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    parent_id  BIGINT NULL,
    CONSTRAINT uq_categories_name UNIQUE (name),
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories(id)
);

CREATE TABLE products (
    id             BIGSERIAL PRIMARY KEY,
    category_id    BIGINT NOT NULL REFERENCES categories(id),
    name           VARCHAR(200) NOT NULL,
    description    TEXT NULL,
    price          NUMERIC(12,2) NOT NULL,
    stock_quantity INT NOT NULL,
    created_at     TIMESTAMP NOT NULL,
    updated_at     TIMESTAMP NOT NULL
);

CREATE INDEX idx_products_category_id ON products(category_id);

CREATE TABLE product_images (
    id         BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES products(id),
    image_url  VARCHAR(500) NOT NULL,
    sort_order SMALLINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_product_images_product_id ON product_images(product_id);

CREATE TABLE inventories (
    id         BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL UNIQUE REFERENCES products(id),
    quantity   INT NOT NULL,
    version    BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE inventory_transactions (
    id               BIGSERIAL PRIMARY KEY,
    inventory_id     BIGINT NOT NULL REFERENCES inventories(id),
    type             VARCHAR(20) NOT NULL,
    quantity_change  INT NOT NULL,
    balance_after    INT NOT NULL,
    order_id         BIGINT NULL,
    reason           VARCHAR(200) NULL,
    created_at       TIMESTAMP NOT NULL
);

CREATE INDEX idx_inventory_transactions_inventory_id ON inventory_transactions(inventory_id);
