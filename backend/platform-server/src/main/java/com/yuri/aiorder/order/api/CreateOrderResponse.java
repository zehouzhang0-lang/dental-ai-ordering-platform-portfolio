package com.yuri.aiorder.order.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public record CreateOrderResponse(
        @JsonProperty("order_id") long orderId,
        @JsonProperty("order_no") String orderNo,
        @JsonProperty("product_type") String productType,
        @JsonProperty("external_status") String externalStatus,
        @JsonProperty("form_data") JsonNode formData) {
}
