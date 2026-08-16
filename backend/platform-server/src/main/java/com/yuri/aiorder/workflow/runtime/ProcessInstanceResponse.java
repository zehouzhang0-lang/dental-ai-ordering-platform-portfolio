package com.yuri.aiorder.workflow.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;

public record ProcessInstanceResponse(
        @JsonProperty("instance_id") long instanceId,
        @JsonProperty("order_id") long orderId,
        @JsonProperty("instance_status") String instanceStatus,
        @JsonProperty("intake_branch_used") String intakeBranchUsed,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt,
        List<ProcessNodeResponse> nodes,
        List<ProcessEdgeResponse> edges) {
}
