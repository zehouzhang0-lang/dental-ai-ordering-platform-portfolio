package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CheckRecordResponse(
        @JsonProperty("check_id") long checkId,
        @JsonProperty("node_instance_id") long nodeInstanceId,
        @JsonProperty("check_type") Integer checkType,
        String result,
        @JsonProperty("rework_id") Long reworkId) {
}
