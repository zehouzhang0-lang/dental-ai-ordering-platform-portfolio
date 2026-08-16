package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record PerformanceDetailResponse(
        @JsonProperty("work_log_id") long workLogId,
        @JsonProperty("order_id") long orderId,
        @JsonProperty("order_no") String orderNo,
        @JsonProperty("node_instance_id") long nodeInstanceId,
        @JsonProperty("node_name") String nodeName,
        @JsonProperty("worker_user_id") long workerUserId,
        String status,
        @JsonProperty("effective_duration") Integer effectiveDuration,
        @JsonProperty("standard_duration") Integer standardDuration,
        @JsonProperty("on_time") Boolean onTime,
        @JsonProperty("started_at") LocalDateTime startedAt,
        @JsonProperty("finished_at") LocalDateTime finishedAt) {
}
