package com.yuri.aiorder.workflow.definition;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WorkflowNodeSummary(
        @JsonProperty("node_id") long nodeId,
        @JsonProperty("process_name") String processName,
        @JsonProperty("step_order") int stepOrder,
        @JsonProperty("is_optional") int isOptional,
        @JsonProperty("branch_group") String branchGroup) {
}
