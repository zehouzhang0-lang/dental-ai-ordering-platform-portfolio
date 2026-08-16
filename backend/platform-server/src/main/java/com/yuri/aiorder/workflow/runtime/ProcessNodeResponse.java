package com.yuri.aiorder.workflow.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record ProcessNodeResponse(
        @JsonProperty("node_instance_id") long nodeInstanceId,
        @JsonProperty("node_code") String nodeCode,
        @JsonProperty("process_name") String processName,
        @JsonProperty("stage_name") String stageName,
        @JsonProperty("node_category") String nodeCategory,
        @JsonProperty("step_order") int stepOrder,
        @JsonProperty("is_optional") int isOptional,
        @JsonProperty("branch_group") String branchGroup,
        @JsonProperty("branch_key") String branchKey,
        @JsonProperty("assigned_user_id") Long assignedUserId,
        @JsonProperty("node_status") String nodeStatus,
        @JsonProperty("standard_duration") Integer standardDuration,
        @JsonProperty("started_at") LocalDateTime startedAt,
        @JsonProperty("deadline_at") LocalDateTime deadlineAt,
        @JsonProperty("completed_at") LocalDateTime completedAt,
        @JsonProperty("can_start") boolean canStart,
        @JsonProperty("start_block_reason") String startBlockReason) {
}
