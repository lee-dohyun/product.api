-- RAG 상품 Q&A 기능(#46)을 위한 임베딩 저장소.
-- vector 확장은 이미 catalogdb에 superuser로 수동 설치됐다(2026-08-27, pgvector 0.8.6) —
-- IF NOT EXISTS는 확장이 이미 있으면 권한 체크 없이 스킵하므로 catalog_user(non-superuser)로도
-- 안전하게 통과한다. 신규(Testcontainers 등) 환경에서는 연결 계정이 superuser여야 이 줄이 실제로
-- 확장을 설치할 수 있다.
CREATE EXTENSION IF NOT EXISTS vector;

-- text-embedding-3-small 기준 1536차원. 다른 모델로 바꾸면 차원이 달라지므로 새 컬럼/테이블로
-- 전환해야 한다(expand-contract는 개발 단계 유예 대상이지만, 벡터 차원 변경 자체가 UPDATE로
-- 안 되는 스키마 변경이라 별도 마이그레이션이 필요하다는 점은 유예와 무관).
CREATE TABLE product_embeddings (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    embedding vector(1536) NOT NULL,
    embedding_model VARCHAR(50) NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_product_embeddings_product_id UNIQUE (product_id)
);

-- 카탈로그 규모가 지금은 데모 수준(수십 건)이라 근사 최근접(ivfflat/hnsw) 인덱스는 아직 불필요 —
-- 시퀀셜 스캔 + <=> 코사인 거리로 충분하다. 카탈로그가 수천 건 이상으로 커지면 그때
-- `CREATE INDEX ... USING hnsw (embedding vector_cosine_ops)`를 추가한다.
