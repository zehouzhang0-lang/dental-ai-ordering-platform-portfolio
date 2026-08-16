package com.yuri.aiorder.order.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OrderReviewRequest(
        String action,
        @JsonProperty("reject_reason") String rejectReason,
        @JsonProperty("production_note") String productionNote) {
}
