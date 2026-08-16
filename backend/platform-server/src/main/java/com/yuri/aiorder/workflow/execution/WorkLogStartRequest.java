package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WorkLogStartRequest(@JsonProperty("node_instance_id") Long nodeInstanceId) {
}
