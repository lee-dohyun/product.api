-- =========================================================
-- 상품당 진행 중인 검수 제출은 1건만 (product.api#75)
--
-- 파트너가 "제출"을 연달아 누르면 두 요청이 모두 "진행 중인 제출 없음"을 보고 제출을 2건 만들 수
-- 있다. 애플리케이션은 상품 행 잠금(PartnerProductService)으로 막지만, 캐논 §3 대로 DB 제약은
-- 애플리케이션 로직과 별개로 둔다 - 관리자 경로(POST /api/submissions)는 그 잠금을 거치지 않는다.
--
-- NEEDS_FIX 도 "진행 중"에 넣는다: 보완 후에는 새 제출이 아니라 같은 제출을 재제출한다
-- (심사 이력이 한 줄로 이어져야 한다). LIVE/PAUSED 는 끝난 제출이라 여러 건이어도 된다.
--
-- 2026-09-28 운영 DB 실측: product_submissions 0건 - 기존 데이터 충돌 없음.
-- =========================================================
CREATE UNIQUE INDEX uq_product_submissions_open_per_product
    ON product_submissions (product_id)
    WHERE status IN ('SUBMITTED', 'VALIDATING', 'IN_REVIEW', 'NEEDS_FIX');
