-- =========================================================
-- 상품 노출 속성 추가 (product.api#28, posselect-shell#102 Phase 2)
--
-- 쿠팡 수준의 카드 정보 밀도(정가+할인율, 별점+리뷰수, 배송배지, 브랜드)를 담기 위한 컬럼.
-- 할인율은 저장하지 않는다 - list_price(정가)와 판매가(product_variants.price)로 매번
-- 파생해야 둘이 갈라지지 않는다.
--
-- rating_avg/review_count는 리뷰 기능이 아직 없는 동안 관리자가 직접 입력하는
-- 비정규화 컬럼이다. 리뷰 기능이 생기면 실제 집계값으로 교체될 자리.
-- =========================================================
ALTER TABLE products ADD COLUMN list_price     NUMERIC(12,2);
ALTER TABLE products ADD COLUMN rating_avg     NUMERIC(2,1);
ALTER TABLE products ADD COLUMN review_count   INT NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN shipping_badge VARCHAR(20);
ALTER TABLE products ADD COLUMN free_shipping  BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE products ADD COLUMN brand          VARCHAR(100);

COMMENT ON COLUMN products.rating_avg IS
    '리뷰 기능 도입 전까지의 비정규화 값(관리자 직접 입력) - 리뷰 기능이 생기면 실제 집계로 교체';
COMMENT ON COLUMN products.review_count IS
    '리뷰 기능 도입 전까지의 비정규화 값(관리자 직접 입력) - 리뷰 기능이 생기면 실제 집계로 교체';

-- ---------------------------------------------------------
-- 데모 상품 백필 (is_demo=TRUE 한정, product.api#27/V8이 만든 카탈로그 대상)
--
-- V8의 상품 설명은 항상 "{브랜드}의 {상품명 나머지}." 형식으로 생성됐다(생성기:
-- ~/msa/scripts/demo-catalog/gen_v8_sql.py). description을 첫 '의' 앞까지 잘라내면
-- 브랜드를 정확히 복원할 수 있다 - 128개 행 전수 검증 완료(0건 불일치). name을 파싱하지
-- 않는 이유는 "도서출판 목"처럼 브랜드 자체에 공백이 들어간 케이스가 있어 단어 단위로
-- 자르면 깨지기 때문이다.
--
-- 옛 "테스트 상품"(id=1)은 description이 "설명"이라 '의'가 없다 - brand는 NULL로 남는다.
-- ---------------------------------------------------------
UPDATE products
   SET brand = split_part(description, '의', 1)
 WHERE is_demo AND position('의' IN description) > 0;

-- 평점 3.5~5.0 사이를 id로 결정적으로 분산
UPDATE products
   SET rating_avg = ROUND((3.5 + ((id % 16) * 0.1))::numeric, 1)
 WHERE is_demo;

-- 리뷰 수 5~487 사이를 id로 결정적으로 분산
UPDATE products
   SET review_count = 5 + (id * 37) % 483
 WHERE is_demo;

-- 약 2/3는 무료배송
UPDATE products
   SET free_shipping = (id % 3 <> 0)
 WHERE is_demo;

-- 배송배지는 일부 상품에만 (판매자로켓 20%, 로켓배송 30%, 나머지 없음)
UPDATE products
   SET shipping_badge = CASE
       WHEN id % 5 = 0 THEN '판매자로켓'
       WHEN id % 2 = 0 THEN '로켓배송'
       ELSE NULL
   END
 WHERE is_demo;

-- 정가 = 활성 variant 중 최저가(대표 판매가)보다 5~35% 높게, 100원 단위로 반올림
UPDATE products p
   SET list_price = ROUND((sub.min_price * (1 + (0.05 + (p.id % 31) * 0.01)))::numeric, -2)
  FROM (
      SELECT product_id, MIN(price) AS min_price
        FROM product_variants
       WHERE active
       GROUP BY product_id
  ) sub
 WHERE p.id = sub.product_id AND p.is_demo;
