package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record ProductionCostRecordRequest(
        @JsonProperty("cost_no") @NotBlank String costNo,
        @JsonProperty("order_id") Long orderId,
        @JsonProperty("node_instance_id") Long nodeInstanceId,
        @JsonProperty("cost_type") @NotBlank String costType,
        @NotNull BigDecimal amount,
        String status,
        @JsonProperty("department_name") String departmentName,
        @JsonProperty("supplier_name") String supplierName,
        String description) {
}
