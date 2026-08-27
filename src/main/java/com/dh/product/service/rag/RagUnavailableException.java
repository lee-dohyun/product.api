package com.dh.product.service.rag;

/**
 * OPENAI_API_KEY 등 RAG 기능에 필요한 설정이 없을 때 던진다. 앱 부팅 자체는 키 없이도
 * 성공해야 하므로(product.api#46 — 키는 아직 발급 전), 실제 호출 시점에만 이 예외로 알린다.
 */
public class RagUnavailableException extends RuntimeException {

    public RagUnavailableException(String message) {
        super(message);
    }
}
