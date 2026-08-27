-- =========================================================
-- wishlist_items.product_id FK에 ON DELETE CASCADE 추가
--
-- V2가 옵션/variant/재고/이력 등 products의 다른 모든 하위 테이블에는 CASCADE를 걸었는데
-- V6(찜 기능)만 빠져 있었다. 그 결과 누군가 찜한 상품을 관리자가 삭제하려 하면
-- FK 위반으로 실패한다 - ProductService.deleteProduct가 wishlist_items를 미리 정리하지
-- 않기 때문이다(product.api#47 조사 중 발견, product.api#52).
--
-- fk_wishlist_product는 V6가 직접 이름을 지어 만든 제약이라(Hibernate 자동 생성이 아님)
-- 이름을 안전하게 하드코딩할 수 있다 - V4 카테고리 제약처럼 동적 조회가 필요 없다.
-- =========================================================
ALTER TABLE wishlist_items DROP CONSTRAINT fk_wishlist_product;
ALTER TABLE wishlist_items
    ADD CONSTRAINT fk_wishlist_product FOREIGN KEY (product_id)
    REFERENCES products(id) ON DELETE CASCADE;
