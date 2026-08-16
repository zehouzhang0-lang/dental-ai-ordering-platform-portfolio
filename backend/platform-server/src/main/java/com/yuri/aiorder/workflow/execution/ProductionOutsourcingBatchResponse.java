package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record ProductionOutsourcingBatchResponse(
        @JsonProperty("outsourcing_id") long outsourcingId,
        @JsonProperty("batch_no") String batchNo,
        @JsonProperty("order_id") long orderId,
        @JsonProperty("order_no") String orderNo,
        @JsonProperty("product_type") String productType,
        @JsonProperty("item_name") String itemName,
        @JsonProperty("supplier_name") String supplierName,
        int quantity,
        String status,
        @JsonProperty("sent_at") LocalDateTime sentAt,
        @JsonProperty("expected_return_at") LocalDateTime expectedReturnAt,
        @JsonProperty("actual_return_at") LocalDateTime actualReturnAt,
        @JsonProperty("is_overdue") boolean overdue,
        @JsonProperty("abnormal_note") String abnormalNote,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt) {
}
