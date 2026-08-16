package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WorkLogResponse(
        @JsonProperty("work_log_id") long workLogId,
        @JsonProperty("node_instance_id") long nodeInstanceId,
        @JsonProperty("worker_user_id") long workerUserId,
        String status,
        @JsonProperty("pause_duration_seconds") int pauseDurationSeconds,
        @JsonProperty("effective_duration_seconds") Integer effectiveDurationSeconds) {
}
