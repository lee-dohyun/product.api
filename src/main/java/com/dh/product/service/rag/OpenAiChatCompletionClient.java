package com.dh.product.service.rag;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OpenAiChatCompletionClient implements ChatCompletionClient {

    private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public OpenAiChatCompletionClient(
            RestClient.Builder restClientBuilder,
            @Value("${openai.api-key:}") String apiKey,
            @Value("${openai.chat-model:gpt-4o-mini}") String model) {
        this.restClient = restClientBuilder.build();
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new RagUnavailableException(
                    "OPENAI_API_KEY가 설정되지 않아 답변을 생성할 수 없습니다");
        }

        ChatApiResponse response = restClient.post()
                .uri(ENDPOINT)
                .header("Authorization", "Bearer " + apiKey)
                .body(Map.of(
                        "model", model,
                        "messages", List.of(
                                Map.of("role", "system", "content", systemPrompt),
                                Map.of("role", "user", "content", userPrompt))))
                .retrieve()
                .body(ChatApiResponse.class);

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new RagUnavailableException("OpenAI 응답이 비어 있습니다");
        }
        return response.choices().get(0).message().content();
    }

    private record ChatApiResponse(List<Choice> choices) {
    }

    private record Choice(ChatMessage message) {
    }

    private record ChatMessage(String content) {
    }
}
