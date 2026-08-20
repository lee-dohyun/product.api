-- V5의 시드 데이터가 posselect-ui/tokens.css에 실존하지 않는 --color-primary/--color-secondary를
-- 하드코딩해서, store.front가 인라인 style로 그대로 적용하면 미정의 CSS 변수라 배경이 투명 처리되어
-- 배너 흰 글씨가 거의 안 보이던 문제(2026-08-16 원인 파악, 2026-08-21 수정). 실제 정의된 토큰인
-- --color-accent(#234e95)/--color-accent-2(#728fab)로 교체.
UPDATE banners SET bg_color = 'var(--color-accent)' WHERE bg_color = 'var(--color-primary)';
UPDATE banners SET bg_color = 'var(--color-accent-2)' WHERE bg_color = 'var(--color-secondary)';
