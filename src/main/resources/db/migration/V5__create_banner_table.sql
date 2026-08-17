CREATE TABLE banners (
    id SERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    subtitle VARCHAR(255),
    image_url VARCHAR(500),
    link VARCHAR(500),
    bg_color VARCHAR(50),
    sort_order INT DEFAULT 0,
    is_active BOOLEAN DEFAULT true
);

INSERT INTO banners (title, subtitle, image_url, link, bg_color, sort_order, is_active)
VALUES
    ('검증된 상품만 엄선했습니다', 'posselect.com 오픈 기념 특별전', NULL, '/', 'var(--color-primary)', 10, true),
    ('새로운 계절, 신상품 입고', '트렌드를 선도하는 상품들을 만나보세요', NULL, '/', 'var(--color-secondary)', 20, true);
