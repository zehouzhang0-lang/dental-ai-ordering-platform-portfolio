package com.yuri.aiorder.ai;

public interface AiModelClient {

    boolean isEnabled();

    AiModelResult complete(String systemPrompt, String userPrompt);
}
