package com.yuri.aiorder.order.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DoctorOrderAssistantReadModel(
        @JsonProperty("order_id") long orderId,
        @JsonProperty("order_no") String orderNo,
        @JsonProperty("external_status") String externalStatus,
        @JsonProperty("public_message") String publicMessage,
        @JsonProperty("bill_status") String billStatus,
        @JsonProperty("logistics_status") String logisticsStatus,
        @JsonProperty("tracking_no") String trackingNo,
        @JsonProperty("visible_message_summary") String visibleMessageSummary) {
}
