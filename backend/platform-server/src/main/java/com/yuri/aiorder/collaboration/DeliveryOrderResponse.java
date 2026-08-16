package com.yuri.aiorder.collaboration;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DeliveryOrderResponse(
        @JsonProperty("order_id") long orderId,
        @JsonProperty("order_no") String orderNo,
        @JsonProperty("product_type") String productType,
        @JsonProperty("external_status") String externalStatus,
        @JsonProperty("bill_status") String billStatus,
        @JsonProperty("payment_status") String paymentStatus,
        String carrier,
        @JsonProperty("tracking_no") String trackingNo,
        @JsonProperty("logistics_status") String logisticsStatus,
        @JsonProperty("last_follow_up_note") String lastFollowUpNote) {
}
