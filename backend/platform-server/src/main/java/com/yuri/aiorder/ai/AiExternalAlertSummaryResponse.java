package com.yuri.aiorder.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;

public record AiExternalAlertSummaryResponse(
        @JsonProperty("status_counts") List<StatusCount> statusCounts,
        @JsonProperty("pending_count") long pendingCount,
        @JsonProperty("sending_count") long sendingCount,
        @JsonProperty("sent_count") long sentCount,
        @JsonProperty("failed_count") long failedCount,
        @JsonProperty("dead_letter_count") long deadLetterCount,
        @JsonProperty("latest_failure") Failure latestFailure,
        @JsonProperty("oldest_pending_created_at") LocalDateTime oldestPendingCreatedAt) {

    public record StatusCount(
            @JsonProperty("send_status") String sendStatus,
            long count) {
    }

    public record Failure(
            @JsonProperty("alert_id") long alertId,
            @JsonProperty("alert_type") String alertType,
            @JsonProperty("send_status") String sendStatus,
            int attempts,
            @JsonProperty("last_error") String lastError,
            @JsonProperty("updated_at") LocalDateTime updatedAt) {
    }
}
