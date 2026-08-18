-- 찜(Wishlist) 테이블 신설
CREATE TABLE wishlist_items (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    product_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_wishlist_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT uq_wishlist_user_product UNIQUE (user_id, product_id)
);

CREATE INDEX idx_wishlist_user_id ON wishlist_items (user_id);
