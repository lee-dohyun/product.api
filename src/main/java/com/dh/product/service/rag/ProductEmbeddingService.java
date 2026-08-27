package com.dh.product.service.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Service;

import com.dh.product.domain.Product;
import com.dh.product.repository.ProductEmbeddingRepository;
import com.dh.product.repository.ProductEmbeddingRepository.NearestMatch;

@Service
public class ProductEmbeddingService {

    private static final String EMBEDDING_MODEL = "text-embedding-3-small";

    private final EmbeddingClient embeddingClient;
    private final ProductEmbeddingRepository embeddingRepository;

    public ProductEmbeddingService(EmbeddingClient embeddingClient, ProductEmbeddingRepository embeddingRepository) {
        this.embeddingClient = embeddingClient;
        this.embeddingRepository = embeddingRepository;
    }

    /**
     * 상품명+설명이 바뀌지 않았으면 재임베딩을 건너뛴다(API 호출 비용 절감).
     *
     * @return 실제로 재생성했으면 true, 해시가 같아 건너뛰었으면 false
     */
    public boolean regenerateIfChanged(Product product) {
        String content = embeddingSource(product);
        String hash = sha256(content);

        boolean unchanged = embeddingRepository.findSourceHash(product.getId())
                .map(existing -> existing.equals(hash))
                .orElse(false);
        if (unchanged) {
            return false;
        }

        float[] embedding = embeddingClient.embed(content);
        embeddingRepository.upsert(product.getId(), embedding, EMBEDDING_MODEL, hash);
        return true;
    }

    /**
     * @return 질의와 코사인 거리가 가까운 순서의 product_id 목록
     */
    public List<Long> searchSimilarProductIds(String query, int topK) {
        float[] queryEmbedding = embeddingClient.embed(query);
        return embeddingRepository.findNearest(queryEmbedding, topK).stream()
                .map(NearestMatch::productId)
                .toList();
    }

    private static String embeddingSource(Product product) {
        String description = product.getDescription() == null ? "" : product.getDescription();
        return product.getName() + "\n" + description;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다", e);
        }
    }
}
