package com.dh.product.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * product_embeddings 테이블 전용 저장소. pgvector의 vector 타입은 Hibernate/JPA가 네이티브로
 * 매핑하지 못해(별도 UserType 의존성 없이는) JdbcTemplate 네이티브 SQL로 직접 다룬다 -
 * 벡터는 `'[0.1,0.2,...]'::vector` 텍스트 리터럴로 바인딩하면 pgvector JDBC 드라이버 확장 없이도
 * 동작한다.
 */
@Repository
public class ProductEmbeddingRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProductEmbeddingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<String> findSourceHash(Long productId) {
        List<String> hashes = jdbcTemplate.query(
                "SELECT source_hash FROM product_embeddings WHERE product_id = ?",
                (rs, rowNum) -> rs.getString("source_hash"),
                productId);
        return hashes.stream().findFirst();
    }

    public void upsert(Long productId, float[] embedding, String model, String sourceHash) {
        jdbcTemplate.update(
                """
                INSERT INTO product_embeddings (product_id, embedding, embedding_model, source_hash, updated_at)
                VALUES (?, ?::vector, ?, ?, now())
                ON CONFLICT (product_id)
                DO UPDATE SET embedding = EXCLUDED.embedding,
                              embedding_model = EXCLUDED.embedding_model,
                              source_hash = EXCLUDED.source_hash,
                              updated_at = now()
                """,
                productId, toVectorLiteral(embedding), model, sourceHash);
    }

    /**
     * 코사인 거리(`<=>`) 기준 최근접 topK. 카탈로그가 데모 규모라 근사 인덱스 없이 시퀀셜
     * 스캔으로 충분하다(V15 마이그레이션 주석 참고).
     */
    public List<NearestMatch> findNearest(float[] queryEmbedding, int topK) {
        return jdbcTemplate.query(
                """
                SELECT product_id, embedding <=> ?::vector AS distance
                FROM product_embeddings
                ORDER BY distance ASC
                LIMIT ?
                """,
                (rs, rowNum) -> new NearestMatch(rs.getLong("product_id"), rs.getDouble("distance")),
                toVectorLiteral(queryEmbedding), topK);
    }

    private static String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder(embedding.length * 8);
        sb.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    public record NearestMatch(Long productId, double distance) {
    }
}
