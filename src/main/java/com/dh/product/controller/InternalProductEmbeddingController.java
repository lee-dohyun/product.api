package com.dh.product.controller;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dh.product.domain.Product;
import com.dh.product.repository.ProductRepository;
import com.dh.product.service.rag.ProductEmbeddingService;

/**
 * RAG 임베딩 배치 재생성(product.api#46). InternalVariantController/InventoryController와 같은
 * 신뢰 경계 — /internal/**은 게이트웨이에 라우트가 없어 외부에서 도달 불가능하다. 상품 쓰기 경로
 * (ProductService)에는 아직 훅을 걸지 않았다 — 그 클래스는 캐시/트랜잭션 규칙이 예민해서(CLAUDE.md
 * 참고) 이번 세션에서는 건드리지 않고, 이 배치 엔드포인트로 필요할 때 수동/주기 실행한다.
 */
@RestController
@RequestMapping("/internal/embeddings")
public class InternalProductEmbeddingController {

    private final ProductRepository productRepository;
    private final ProductEmbeddingService embeddingService;

    public InternalProductEmbeddingController(
            ProductRepository productRepository, ProductEmbeddingService embeddingService) {
        this.productRepository = productRepository;
        this.embeddingService = embeddingService;
    }

    public record ReindexResult(int total, int regenerated, int skippedUnchanged) {
    }

    @PostMapping("/reindex")
    public ReindexResult reindexAll() {
        List<Product> products = productRepository.findAll();
        int regenerated = 0;
        for (Product product : products) {
            if (embeddingService.regenerateIfChanged(product)) {
                regenerated++;
            }
        }
        return new ReindexResult(products.size(), regenerated, products.size() - regenerated);
    }
}
