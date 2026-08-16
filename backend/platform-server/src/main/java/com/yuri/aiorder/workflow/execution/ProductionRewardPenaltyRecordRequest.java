package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record ProductionRewardPenaltyRecordRequest(
        @JsonProperty("record_no") @NotBlank String recordNo,
        @JsonProperty("record_type") @NotBlank String recordType,
        @JsonProperty("reason_category") @NotBlank String reasonCategory,
        @NotNull BigDecimal amount,
        String status,
        @JsonProperty("order_id") Long orderId,
        @JsonProperty("node_instance_id") Long nodeInstanceId,
        @JsonProperty("employee_user_id") Long employeeUserId,
        @JsonProperty("department_name") String departmentName,
        String description) {
}
