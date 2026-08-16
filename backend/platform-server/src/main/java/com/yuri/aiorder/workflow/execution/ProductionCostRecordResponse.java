package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record ProductionCostRecordResponse(
        @JsonProperty("cost_id") long costId,
        @JsonProperty("cost_no") String costNo,
        @JsonProperty("order_id") Long orderId,
        @JsonProperty("node_instance_id") Long nodeInstanceId,
        @JsonProperty("cost_type") String costType,
        double amount,
        String status,
        @JsonProperty("department_name") String departmentName,
        @JsonProperty("supplier_name") String supplierName,
        String description,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt,
        @JsonProperty("confirmed_at") LocalDateTime confirmedAt) {
}
