package com.yuri.aiorder.ai;

import dev.langchain4j.model.openai.OpenAiChatModel;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class LangChainDeepSeekAiModelClient implements AiModelClient {

    private static final String DEFAULT_DEEPSEEK_MODEL = "deepseek-chat";

    private final AiGatewayProperties properties;
    private final DeepSeekAiModelClient directDeepSeekClient;

    public LangChainDeepSeekAiModelClient(AiGatewayProperties properties) {
        this.properties = properties;
        this.directDeepSeekClient = new DeepSeekAiModelClient(properties);
    }

    @Override
    public boolean isEnabled() {
        return properties.deepSeekEnabled() || properties.langChainDeepSeekEnabled();
    }

    @Override
    public AiModelResult complete(String systemPrompt, String userPrompt) {
        if (!properties.langChainDeepSeekEnabled()) {
            return directDeepSeekClient.complete(systemPrompt, userPrompt);
        }
        AiGatewayProperties.DeepSeek deepSeek = properties.getDeepseek();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(trimTrailingSlash(deepSeek.getBaseUrl()))
                .apiKey(deepSeek.getApiKey())
                .modelName(deepSeek.getModel())
                .temperature(deepSeek.getTemperature())
                .maxTokens(deepSeek.getMaxTokens())
                .timeout(Duration.ofSeconds(Math.max(1, deepSeek.getReadTimeoutSeconds())))
                .maxRetries(0)
                .build();
        String prompt = """
                System:
                %s

                User:
                %s
                """.formatted(systemPrompt, userPrompt);
        String answer = model.chat(prompt);
        if (answer == null || answer.isBlank()) {
            throw new IllegalStateException("empty LangChain DeepSeek answer");
        }
        return new AiModelResult(
                answer,
                "langchain-" + deepSeek.getModel(),
                estimateTokenCount(prompt),
                estimateTokenCount(answer));
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
