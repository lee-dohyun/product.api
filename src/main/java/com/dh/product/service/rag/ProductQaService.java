package com.dh.product.service.rag;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.dh.product.dto.ProductDtos.ProductSummaryResponse;
import com.dh.product.dto.ProductQaDtos.ProductQaResponse;
import com.dh.product.service.ProductService;

/**
 * 검색(retrieval) + 생성(generation) 두 단계 — 순수 오케스트레이션이라 트랜잭션을 걸지 않는다
 * (원격 HTTP 호출 2회를 트랜잭션 안에 두지 말 것: 캐논 §3).
 */
@Service
public class ProductQaService {

    private static final int TOP_K = 5;
    private static final String SYSTEM_PROMPT = """
            당신은 쇼핑몰 상품 안내 도우미입니다. 아래 제공된 상품 목록 정보만 근거로 답변하세요.
            목록에 없는 상품을 지어내지 말고, 적합한 상품이 없으면 없다고 답하세요. 한국어로,
            2~3문장 이내로 간결하게 답변하세요.
            """;

    private final ProductEmbeddingService embeddingService;
    private final ProductService productService;
    private final ChatCompletionClient chatCompletionClient;

    public ProductQaService(
            ProductEmbeddingService embeddingService,
            ProductService productService,
            ChatCompletionClient chatCompletionClient) {
        this.embeddingService = embeddingService;
        this.productService = productService;
        this.chatCompletionClient = chatCompletionClient;
    }

    public ProductQaResponse answer(String question) {
        List<Long> productIds = embeddingService.searchSimilarProductIds(question, TOP_K);
        List<ProductSummaryResponse> products = productService.getSummariesByIds(productIds);

        if (products.isEmpty()) {
            return new ProductQaResponse("조건에 맞는 상품을 찾지 못했습니다.", List.of());
        }

        String userPrompt = buildUserPrompt(question, products);
        String answer = chatCompletionClient.complete(SYSTEM_PROMPT, userPrompt);
        return new ProductQaResponse(answer, products);
    }

    private static String buildUserPrompt(String question, List<ProductSummaryResponse> products) {
        String catalogText = products.stream()
                .map(p -> "- %s (%,d원, 재고 %d개)".formatted(p.name(), p.price().longValue(), p.stockQuantity()))
                .collect(Collectors.joining("\n"));
        return "질문: " + question + "\n\n상품 목록:\n" + catalogText;
    }
}
