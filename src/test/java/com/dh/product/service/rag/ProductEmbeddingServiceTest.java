package com.dh.product.service.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.dh.product.domain.Product;
import com.dh.product.repository.ProductEmbeddingRepository;

class ProductEmbeddingServiceTest {

    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final ProductEmbeddingRepository embeddingRepository = mock(ProductEmbeddingRepository.class);
    private final ProductEmbeddingService service = new ProductEmbeddingService(embeddingClient, embeddingRepository);

    @Test
    void 이전_임베딩이_없으면_새로_생성한다() {
        Product product = productWithId(1L, "티셔츠", "면 100%");
        when(embeddingRepository.findSourceHash(1L)).thenReturn(Optional.empty());
        when(embeddingClient.embed(any())).thenReturn(new float[] {0.1f});

        boolean changed = service.regenerateIfChanged(product);

        assertThat(changed).isTrue();
        verify(embeddingClient).embed("티셔츠\n면 100%");
        verify(embeddingRepository).upsert(eq(1L), any(), eq("text-embedding-3-small"), any());
    }

    @Test
    void 상품명과_설명이_그대로면_재임베딩을_건너뛴다() {
        Product product = productWithId(1L, "티셔츠", "면 100%");
        String sameHash = sha256("티셔츠\n면 100%");
        when(embeddingRepository.findSourceHash(1L)).thenReturn(Optional.of(sameHash));

        boolean changed = service.regenerateIfChanged(product);

        assertThat(changed).isFalse();
        verifyNoInteractions(embeddingClient);
    }

    @Test
    void 설명이_바뀌면_해시가_달라져_다시_생성한다() {
        Product product = productWithId(1L, "티셔츠", "새로운 설명");
        when(embeddingRepository.findSourceHash(1L)).thenReturn(Optional.of(sha256("티셔츠\n예전 설명")));
        when(embeddingClient.embed(any())).thenReturn(new float[] {0.2f});

        boolean changed = service.regenerateIfChanged(product);

        assertThat(changed).isTrue();
        verify(embeddingClient).embed("티셔츠\n새로운 설명");
    }

    @Test
    void 질의를_임베딩해_최근접_product_id를_순서대로_반환한다() {
        when(embeddingClient.embed("가성비 티셔츠")).thenReturn(new float[] {0.3f});
        when(embeddingRepository.findNearest(any(), eq(3))).thenReturn(java.util.List.of(
                new ProductEmbeddingRepository.NearestMatch(9001L, 0.01),
                new ProductEmbeddingRepository.NearestMatch(9002L, 0.2)));

        java.util.List<Long> ids = service.searchSimilarProductIds("가성비 티셔츠", 3);

        assertThat(ids).containsExactly(9001L, 9002L);
    }

    private static Product productWithId(Long id, String name, String description) {
        Product product = new Product();
        product.setId(id);
        product.setName(name);
        product.setDescription(description);
        return product;
    }

    private static String sha256(String text) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
