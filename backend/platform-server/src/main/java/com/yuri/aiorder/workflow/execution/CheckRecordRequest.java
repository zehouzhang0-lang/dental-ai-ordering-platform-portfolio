package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CheckRecordRequest(
        @JsonProperty("node_instance_id") Long nodeInstanceId,
        @JsonProperty("check_type") Integer checkType,
        @JsonProperty("is_pass") Boolean isPass,
        String remark,
        @JsonProperty("rework_to_node_id") Long reworkToNodeId) {
}
