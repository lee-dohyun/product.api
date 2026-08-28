-- =========================================================
-- 판매자(입점 심사) 도입 1단계 (product.api#29, gateway#212 0단계 후속)
--
-- 지금까지 partner/seller/offer/vendor 개념이 코드 어디에도 없었다 - 공급사 한 곳을
-- 들이는 것조차 표현할 자리가 없는 상태였다. order.api V6(order.api#13)이 order_items에
-- seller_id/seller_name 스냅샷을 이미 넣었고 그때 자사를 id=1로 고정했다 - 여기서도
-- 같은 id=1을 자사 판매자로 맞춘다.
--
-- 상태머신은 boolean approved 하나가 아니라 게이트 6개를 지나는 전이다(조사한 5개 몰
-- 공통 패턴) - 전이 검증은 애플리케이션(SellerService)이 맡고, 여기서는 값 자체를
-- 제약하지 않는다(CHECK 제약으로 전이 규칙까지 표현하면 재검토 대상 상태가 늘 때마다
-- 마이그레이션이 필요해진다).
-- =========================================================
CREATE TABLE sellers (
    id                       BIGSERIAL PRIMARY KEY,
    name                     VARCHAR(200) NOT NULL,
    business_registration_no VARCHAR(20)  NOT NULL,
    mail_order_sales_no      VARCHAR(30)  NULL,
    representative_name      VARCHAR(100) NOT NULL,
    address                  VARCHAR(300) NOT NULL,
    phone                    VARCHAR(30)  NOT NULL,
    email                    VARCHAR(200) NOT NULL,
    status                   VARCHAR(20)  NOT NULL,
    type                     VARCHAR(20)  NOT NULL,
    settlement_bank          VARCHAR(50)  NULL,
    settlement_account       VARCHAR(50)  NULL,
    shipping_origin_address  VARCHAR(300) NULL,
    return_address           VARCHAR(300) NULL,
    shipping_fee_policy      VARCHAR(500) NULL,
    cs_contact               VARCHAR(100) NULL,
    created_at               TIMESTAMP NOT NULL DEFAULT now(),
    updated_at               TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON COLUMN sellers.status IS
    'DRAFT/SUBMITTED/IN_REVIEW/ACTIVE/SUSPENDED/TERMINATED/REJECTED - 전이 검증은 SellerService';
COMMENT ON COLUMN sellers.type IS 'FIRST_PARTY(자사) / SUPPLIER(공급사) / SELLER(3P, 현재 미사용)';

CREATE TABLE seller_documents (
    id            BIGSERIAL PRIMARY KEY,
    seller_id     BIGINT NOT NULL REFERENCES sellers(id) ON DELETE CASCADE,
    doc_type      VARCHAR(50)  NOT NULL,
    file_key      VARCHAR(500) NOT NULL,
    issued_at     DATE NULL,
    verified_at   TIMESTAMP NULL,
    reject_reason VARCHAR(500) NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_seller_documents_seller_id ON seller_documents(seller_id);

-- 심사는 사후 설명 책임이 있는 행위다 - 누가/언제/왜 상태를 바꿨는지는 seller 행 자체가
-- 아니라 별도 이력 테이블에 append-only로 남긴다.
CREATE TABLE seller_status_history (
    id           BIGSERIAL PRIMARY KEY,
    seller_id    BIGINT NOT NULL REFERENCES sellers(id) ON DELETE CASCADE,
    from_status  VARCHAR(20) NULL,
    to_status    VARCHAR(20) NOT NULL,
    reason_code  VARCHAR(50) NULL,
    reason_note  VARCHAR(500) NULL,
    changed_by   VARCHAR(200) NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_seller_status_history_seller_id ON seller_status_history(seller_id);

-- ---------------------------------------------------------
-- products.seller_id / products.status
--
-- seller_id는 FK다(order_items.seller_id와 다르다 - 그건 주문 시점 스냅샷이라 판매자가
-- 바뀌어도 과거 주문은 안 바뀌어야 해서 FK를 안 걸었다. products.seller_id는 반대로
-- "지금 이 상품의 주인이 누구인가"를 항상 최신으로 반영해야 하므로 참조다).
-- ---------------------------------------------------------
ALTER TABLE products ADD COLUMN seller_id BIGINT;
ALTER TABLE products ADD COLUMN status VARCHAR(20);

-- 자사 판매자 시드 - order.api V6가 이미 order_items.seller_id=1 / seller_name='포스셀렉트'로
-- 고정했으므로 여기서도 id=1, 상호를 맞춘다. business_registration_no는 실제 사업자등록번호가
-- 아니라 자리표시자다 - 실 서비스 시작 시점(사용자 별도 통지)에 반드시 실제 값으로 교체할 것.
INSERT INTO sellers (
    id, name, business_registration_no, representative_name, address, phone, email,
    status, type, created_at, updated_at
) VALUES (
    1, '포스셀렉트', '000-00-00000', '대표자 미정', '주소 미정', '000-0000-0000',
    'customer-service@leedohyun.com', 'ACTIVE', 'FIRST_PARTY', now(), now()
);

-- 시퀀스가 명시적으로 넣은 id=1과 어긋나지 않도록 맞춘다.
SELECT setval(pg_get_serial_sequence('sellers', 'id'), 1, true);

INSERT INTO seller_status_history (seller_id, from_status, to_status, reason_note, changed_by, created_at)
VALUES (1, NULL, 'ACTIVE', '초기 시딩 - 자사 판매자', 'system', now());

-- 기존 상품은 전부 자사 판매이고 이미 판매 중이었다(삭제 외에는 노출을 끌 방법이 없었다) -
-- 그 사실을 그대로 옮긴다.
UPDATE products SET seller_id = 1, status = 'LIVE' WHERE seller_id IS NULL;

ALTER TABLE products ALTER COLUMN seller_id SET NOT NULL;
ALTER TABLE products ALTER COLUMN status SET NOT NULL;
ALTER TABLE products ADD CONSTRAINT fk_products_seller FOREIGN KEY (seller_id) REFERENCES sellers(id);

CREATE INDEX idx_products_seller_id ON products(seller_id);

COMMENT ON COLUMN products.status IS 'DRAFT/LIVE/PAUSED/ARCHIVED - 전이 검증 없음(단순 노출 스위치)';
