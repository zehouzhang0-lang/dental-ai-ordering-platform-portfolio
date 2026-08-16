package com.yuri.aiorder.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public class DeepSeekAiModelClient implements AiModelClient {

    private final AiGatewayProperties properties;
    private final RestClient restClient;

    public DeepSeekAiModelClient(AiGatewayProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory(properties.getDeepseek()))
                .build();
    }

    @Override
    public boolean isEnabled() {
        return properties.deepSeekEnabled();
    }

    @Override
    public AiModelResult complete(String systemPrompt, String userPrompt) {
        AiGatewayProperties.DeepSeek deepSeek = properties.getDeepseek();
        JsonNode response = restClient.post()
                .uri(trimTrailingSlash(deepSeek.getBaseUrl()) + "/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + deepSeek.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "model", deepSeek.getModel(),
                        "stream", false,
                        "temperature", deepSeek.getTemperature(),
                        "max_tokens", deepSeek.getMaxTokens(),
                        "messages", List.of(
                                Map.of("role", "system", "content", systemPrompt),
                                Map.of("role", "user", "content", userPrompt))))
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            throw new IllegalStateException("empty DeepSeek response");
        }
        String content = response.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new IllegalStateException("empty DeepSeek answer");
        }
        JsonNode usage = response.path("usage");
        int inputTokens = usage.path("prompt_tokens").asInt(estimateTokenCount(systemPrompt + "\n" + userPrompt));
        Integer outputTokens = usage.has("completion_tokens")
                ? usage.path("completion_tokens").asInt()
                : null;
        return new AiModelResult(content, deepSeek.getModel(), inputTokens, outputTokens);
    }

    private SimpleClientHttpRequestFactory requestFactory(AiGatewayProperties.DeepSeek deepSeek) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.max(1, deepSeek.getConnectTimeoutSeconds())));
        factory.setReadTimeout(Duration.ofSeconds(Math.max(1, deepSeek.getReadTimeoutSeconds())));
        return factory;
    }

    private String trimTrailingSlash(String value) {
        String baseUrl = value == null || value.isBlank() ? "https://api.deepseek.com" : value.trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private int estimateTokenCount(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return Math.max(1, value.trim().length() / 2);
    }
}
