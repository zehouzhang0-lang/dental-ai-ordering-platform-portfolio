package com.yuri.aiorder.patient;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record PatientOrderResponse(
        @JsonProperty("order_id") long orderId,
        @JsonProperty("order_no") String orderNo,
        @JsonProperty("product_type") String productType,
        @JsonProperty("external_status") String externalStatus,
        @JsonProperty("created_at") LocalDateTime createdAt) {
}
