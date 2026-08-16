package com.yuri.aiorder.workflow.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record ProductionQuestionResponse(
        @JsonProperty("question_id") long questionId,
        @JsonProperty("order_id") long orderId,
        @JsonProperty("node_instance_id") long nodeInstanceId,
        String content,
        String status,
        @JsonProperty("asked_at") LocalDateTime askedAt,
        @JsonProperty("resolved_at") LocalDateTime resolvedAt) {
}
