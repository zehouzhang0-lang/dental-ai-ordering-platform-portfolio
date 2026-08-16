package com.yuri.aiorder.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;

public record AiExternalAlertListResponse(
        int limit,
        List<Record> records) {

    public record Record(
            @JsonProperty("alert_id") long alertId,
            @JsonProperty("event_type") String eventType,
            @JsonProperty("send_status") String sendStatus,
            @JsonProperty("created_at") LocalDateTime createdAt,
            @JsonProperty("updated_at") LocalDateTime updatedAt,
            int attempts,
            @JsonProperty("last_error") String lastError,
            @JsonProperty("last_attempted_at") LocalDateTime lastAttemptedAt) {
    }
}
