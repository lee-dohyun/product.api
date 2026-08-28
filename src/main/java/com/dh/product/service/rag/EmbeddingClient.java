package com.dh.product.service.rag;

/**
 * 텍스트를 벡터 임베딩으로 변환하는 클라이언트. 벤더(OpenAI/Voyage 등)를 교체할 수 있게
 * 인터페이스로 분리한다 - 지금은 OpenAI 구현체 하나뿐이다(product.api#46).
 */
public interface EmbeddingClient {

    /**
     * @return text-embedding-3-small 기준 1536차원 벡터. 다른 모델을 쓰면 차원이 달라지므로
     *         호출자가 저장 스키마(vector(1536))와 맞는 모델을 쓰고 있는지 책임진다.
     */
    float[] embed(String text);
}
