package com.yuri.aiorder.workflow.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NodeActionResponse(
        @JsonProperty("node_instance_id") long nodeInstanceId,
        @JsonProperty("node_status") String nodeStatus) {
}
