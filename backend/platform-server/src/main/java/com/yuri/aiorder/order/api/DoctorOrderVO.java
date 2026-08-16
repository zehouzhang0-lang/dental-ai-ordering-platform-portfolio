package com.yuri.aiorder.order.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;

public record DoctorOrderVO(
        @JsonProperty("order_id") long orderId,
        @JsonProperty("order_no") String orderNo,
        @JsonProperty("group_id") Long groupId,
        @JsonProperty("patient_id") Long patientId,
        @JsonProperty("product_type") String productType,
        @JsonProperty("external_status") String externalStatus,
        boolean editable,
        @JsonProperty("form_data") JsonNode formData,
        @JsonProperty("public_message") String publicMessage,
        @JsonProperty("bill_status") String billStatus,
        @JsonProperty("logistics_status") String logisticsStatus,
        @JsonProperty("tracking_no") String trackingNo,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt,
        @JsonProperty("public_progress") List<DoctorOrderProgressItem> publicProgress) {
}
