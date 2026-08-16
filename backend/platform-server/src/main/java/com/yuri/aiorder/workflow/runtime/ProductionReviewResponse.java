package com.yuri.aiorder.workflow.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ProductionReviewResponse(
        @JsonProperty("order_id") long orderId,
        @JsonProperty("instance_id") Long instanceId,
        @JsonProperty("internal_status") String internalStatus,
        @JsonProperty("external_status") String externalStatus) {
}
