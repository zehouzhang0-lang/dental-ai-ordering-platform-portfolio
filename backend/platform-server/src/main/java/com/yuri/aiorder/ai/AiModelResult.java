package com.yuri.aiorder.ai;

public record AiModelResult(
        String content,
        String modelName,
        int inputTokenCount,
        Integer outputTokenCount) {
}
