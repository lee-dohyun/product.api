-- =========================================================
-- 오퍼(offer) 분리 3단계 (product.api#31, gateway#212)
--
-- 배경: 지금 Product/ProductVariant 하나가 "카탈로그(무엇인가)"와 "판매 단위(누가 얼마에
-- 파는가)"를 겸하고 있다. 같은 상품에 두 번째 판매자가 붙는 순간 표현할 자리가 없다 -
-- Amazon ASIN 매칭도 쿠팡 아이템위너도 같은 상품에 여러 오퍼가 붙고 그중 하나가 대표로
-- 노출되는 구조다.
--
-- 1P 에서는 오퍼가 판매자당 1건이라 사실상 SKU 와 1:1 이지만, 지금 분리해 두면 3P 전환이
-- 재설계가 아니라 추가 작업이 된다.
--
-- 재고 소유: 1P 는 기존 inventories(variant 단위)를 그대로 쓴다. 오퍼별 재고는 3P 도입
-- 시점에 확장한다 - 지금 오퍼별 재고로 옮기면 쓰지도 않을 마이그레이션 위험만 진다.
-- =========================================================
CREATE TABLE offers (
    id                  BIGSERIAL PRIMARY KEY,
    seller_id           BIGINT NOT NULL REFERENCES sellers(id),
    product_variant_id  BIGINT NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    price               NUMERIC(12,2) NOT NULL,
    status              VARCHAR(20) NOT NULL,
    shipping_fee        NUMERIC(12,2) NULL,
    free_shipping       BOOLEAN NOT NULL DEFAULT FALSE,
    lead_time_days      SMALLINT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP NOT NULL DEFAULT now(),
    -- 판매자 1명이 같은 SKU 에 오퍼를 두 개 걸 수 없다. 3P 에서 대표 오퍼 선정이
    -- "판매자별 1건 중 고르기"로 단순해지는 전제이기도 하다.
    CONSTRAINT uq_offers_seller_variant UNIQUE (seller_id, product_variant_id)
);

CREATE INDEX idx_offers_product_variant_id ON offers(product_variant_id);
CREATE INDEX idx_offers_seller_id ON offers(seller_id);

COMMENT ON COLUMN offers.status IS 'ACTIVE / PAUSED / ENDED - 대표 오퍼 후보는 ACTIVE 만';
COMMENT ON COLUMN offers.price IS
    '이 판매자가 이 SKU 를 파는 가격. 주문 금액의 유일한 출처다(클라이언트가 보낸 가격은 절대 신뢰하지 않는다 - product.api#5)';
COMMENT ON COLUMN offers.lead_time_days IS '출고 리드타임(일). 3P 에서 판매자별로 달라진다';

-- ---------------------------------------------------------
-- 자사 오퍼 백필 — 기존 variant 마다 seller_id=1 오퍼 1건
--
-- variant 의 현재 가격/활성 여부를 그대로 옮긴다. 이 시점 이후로 "판매 가격"의 진실은
-- offers.price 지만, product_variants.price 를 지우지는 않는다 - order.api 가 아직
-- /internal/variants/resolve 를 쓰고 있어서 한 배포 주기 동안 병행해야 한다(이슈 §설계상 주의).
-- 제거는 order.api 전환이 끝난 뒤 별도 마이그레이션으로.
-- ---------------------------------------------------------
INSERT INTO offers (seller_id, product_variant_id, price, status, free_shipping, created_at, updated_at)
SELECT COALESCE(p.seller_id, 1),
       v.id,
       v.price,
       CASE WHEN v.active THEN 'ACTIVE' ELSE 'PAUSED' END,
       COALESCE(p.free_shipping, FALSE),
       now(),
       now()
  FROM product_variants v
  JOIN products p ON p.id = v.product_id;
