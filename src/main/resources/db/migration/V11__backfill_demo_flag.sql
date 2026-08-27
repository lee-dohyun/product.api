-- =========================================================
-- is_demo 격리 플래그 백필 (product.api#49)
--
-- V8이 시드한 상품/카테고리/배너를 전부 is_demo=TRUE로 넣도록 작성돼 있고
-- flyway_schema_history에도 V8~V10이 체크섬 불일치 없이 성공 기록으로 남아 있는데,
-- 운영 DB를 실측하면 세 테이블 전부 is_demo=TRUE인 행이 0건이었다. 애플리케이션
-- 코드(엔티티에 is_demo가 매핑돼 있지 않음)와 마이그레이션 파일(V9/V10 어디에도
-- is_demo를 되돌리는 UPDATE가 없음)을 소거해도 원인을 특정하지 못했다 - 이 마이그레이션은
-- 원인 수정이 아니라 관측된 상태를 되돌리는 것이다.
--
-- 지금 되돌려도 안전한 이유: 되돌리는 시점의 운영 DB 실측에서 products/categories/banners
-- 전량이 V8~V10이 시드한 개수와 정확히 일치했다(products 129 = 시드 128 + 옛 "테스트 상품" 1건,
-- categories 34 = 대분류 8 + 중분류 26, banners 8). admin.front를 통해 만들어진 실상품이
-- 하나도 없다는 뜻이므로, 전량을 TRUE로 되돌려도 실상품을 데모로 잘못 표시할 위험이 없다.
--
-- WHERE is_demo = FALSE로 제한하는 이유: 혹시 이 마이그레이션이 실행되기 전 사이에
-- 실상품이 하나라도 만들어졌다면(가능성은 낮지만) 그 상품도 무조건 TRUE로 덮이는 것을
-- 막을 수는 없다 - Flyway 마이그레이션은 조건부 로직을 이 이상 정교하게 걸 수단이 없다.
-- 이 마이그레이션은 반드시 실상품 등록이 시작되기 전에 배포할 것.
-- =========================================================
UPDATE products   SET is_demo = TRUE WHERE is_demo = FALSE;
UPDATE categories SET is_demo = TRUE WHERE is_demo = FALSE;
UPDATE banners    SET is_demo = TRUE WHERE is_demo = FALSE;
