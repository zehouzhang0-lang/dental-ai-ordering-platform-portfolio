package com.yuri.aiorder.collaboration;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LogisticsResponse(
        @JsonProperty("logistics_id") Long logisticsId,
        @JsonProperty("order_id") long orderId,
        String carrier,
        @JsonProperty("tracking_no") String trackingNo,
        @JsonProperty("logistics_status") String logisticsStatus) {
}
