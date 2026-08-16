package com.yuri.aiorder.workflow.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MyTaskResponse(
        @JsonProperty("node_instance_id") long nodeInstanceId,
        @JsonProperty("order_id") long orderId,
        @JsonProperty("order_no") String orderNo,
        @JsonProperty("process_name") String processName,
        @JsonProperty("node_status") String nodeStatus,
        @JsonProperty("standard_duration") Integer standardDuration,
        @JsonProperty("can_start") boolean canStart,
        @JsonProperty("start_block_reason") String startBlockReason) {
}
