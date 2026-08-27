package com.dh.product.service.rag;

/**
 * 검색으로 찾은 상품 정보를 근거로 자연어 답변을 생성하는 클라이언트.
 */
public interface ChatCompletionClient {

    String complete(String systemPrompt, String userPrompt);
}
