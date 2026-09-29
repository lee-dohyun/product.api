-- =========================================================
-- 상품 판매 정책 (product.api#79)
--
-- 과세 구분·KC 인증·배송/반품 정책·판매 기간·구매 수량. 상품 1개당 1행.
--
-- products 에 컬럼을 늘리지 않고 테이블을 분리한 이유: 상품 단건 응답(ProductResponse)이 Redis 에
-- 고정 타입으로 캐시된다. 거기에 필드를 더하면 배포 직후 기존 캐시 엔트리와 모양이 달라진다
-- (CLAUDE.md "캐시되는 DTO 필드" 항목). 정책은 별도 리소스(.../policy)로 읽는다.
--
-- 값 목록(tax_type 등)에 CHECK 제약을 두지 않는다: 값이 늘 때마다 ALTER 가 필요해지고(캐논 §3
-- enum CHECK 함정), 검증은 애플리케이션 enum 이 한다. sellers.status 와 같은 판단.
-- 용어: gateway Wiki Glossary §8-2.
-- =========================================================
CREATE TABLE product_policies (
    product_id              BIGINT PRIMARY KEY REFERENCES products(id) ON DELETE CASCADE,
    tax_type                VARCHAR(20)   NULL,
    kc_cert_type            VARCHAR(30)   NULL,
    kc_cert_number          VARCHAR(100)  NULL,
    shipping_fee_type       VARCHAR(20)   NULL,
    shipping_fee            NUMERIC(12,2) NULL,
    free_shipping_threshold NUMERIC(12,2) NULL,
    shipping_lead_days      SMALLINT      NULL,
    jeju_extra_fee          NUMERIC(12,2) NULL,
    island_extra_fee        NUMERIC(12,2) NULL,
    return_shipping_fee     NUMERIC(12,2) NULL,
    exchange_shipping_fee   NUMERIC(12,2) NULL,
    return_address          VARCHAR(300)  NULL,
    sale_start_at           TIMESTAMP     NULL,
    sale_end_at             TIMESTAMP     NULL,
    max_purchase_quantity   INTEGER       NULL,
    updated_at              TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT product_policies_sale_period CHECK (sale_start_at IS NULL OR sale_end_at IS NULL OR sale_start_at < sale_end_at),
    CONSTRAINT product_policies_max_qty_positive CHECK (max_purchase_quantity IS NULL OR max_purchase_quantity > 0)
);
COMMENT ON TABLE product_policies IS '상품 판매 정책(과세·KC·배송/반품·판매기간·구매수량). Glossary §8-2, product.api#79';
