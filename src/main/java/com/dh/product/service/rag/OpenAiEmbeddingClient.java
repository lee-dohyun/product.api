package com.dh.product.service.rag;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private static final String ENDPOINT = "https://api.openai.com/v1/embeddings";

    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public OpenAiEmbeddingClient(
            RestClient.Builder restClientBuilder,
            @Value("${openai.api-key:}") String apiKey,
            @Value("${openai.embedding-model:text-embedding-3-small}") String model) {
        this.restClient = restClientBuilder.build();
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public float[] embed(String text) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new RagUnavailableException(
                    "OPENAI_API_KEY가 설정되지 않아 임베딩을 생성할 수 없습니다");
        }

        EmbeddingApiResponse response = restClient.post()
                .uri(ENDPOINT)
                .header("Authorization", "Bearer " + apiKey)
                .body(Map.of("model", model, "input", text))
                .retrieve()
                .body(EmbeddingApiResponse.class);

        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new RagUnavailableException("OpenAI 임베딩 응답이 비어 있습니다");
        }
        return response.data().get(0).embedding();
    }

    private record EmbeddingApiResponse(List<EmbeddingData> data) {
    }

    private record EmbeddingData(float[] embedding) {
    }
}
