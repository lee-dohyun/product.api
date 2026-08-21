-- =========================================================
-- 데모 카탈로그 이미지 v2 전환 (product.api#36)
--
-- 이미지에 PosSelect 브랜드를 넣었다 - 상품은 심볼 마크(우상단 DEMO 아래),
-- 배너는 워드마크(좌하단). DEMO 표기는 그대로 유지한다.
--
-- 왜 같은 키로 덮어쓰지 않고 마이그레이션까지 필요한가:
--   CDN 응답이 Cache-Control: max-age=31536000(1년)이다. 같은 키로 덮어쓰면
--   next/image 디스크 캐시와 방문자 브라우저가 1년 동안 옛 이미지를 붙든다.
--   curl 로는 중간 캐시가 없어 즉시 보이므로 "올렸는데 화면만 그대로"로 오진하기 쉽다.
--   그래서 키에 버전을 넣고(products/v2/..., banners/v2/...) 여기서 URL 을 옮긴다.
--
-- 이미지 실체는 이미 CDN 에 올라가 있다(392개). 이 마이그레이션은 참조만 바꾼다.
-- =========================================================

-- 상품 이미지: V8 이 넣은 데모 상품(id 9000번대)만 대상.
--
-- is_demo 로 거르지 않고 id 대역으로 거르는 이유: 옛 "테스트 상품"(id=1)도 V8 에서
-- is_demo=TRUE 로 표시했는데, 그 이미지(test-product-1.png)는 생성기가 만든 자산이
-- 아니라서 v2 가 존재하지 않는다. is_demo 로 걸면 없는 URL 을 가리키게 된다.
UPDATE product_images
   SET image_url = replace(image_url, '/cdn/products/', '/cdn/products/v2/')
 WHERE product_id >= 9001
   AND image_url LIKE '%/cdn/products/%'
   AND image_url NOT LIKE '%/cdn/products/v2/%';

-- 배너: V8 이 넣은 8건이 전부이고 모두 생성기 자산이다.
UPDATE banners
   SET image_url = replace(image_url, '/cdn/banners/', '/cdn/banners/v2/')
 WHERE is_demo
   AND image_url LIKE '%/cdn/banners/%'
   AND image_url NOT LIKE '%/cdn/banners/v2/%';
