package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record ProductionRewardPenaltyRecordResponse(
        @JsonProperty("record_id") long recordId,
        @JsonProperty("record_no") String recordNo,
        @JsonProperty("record_type") String recordType,
        @JsonProperty("reason_category") String reasonCategory,
        double amount,
        String status,
        @JsonProperty("order_id") Long orderId,
        @JsonProperty("node_instance_id") Long nodeInstanceId,
        @JsonProperty("employee_user_id") Long employeeUserId,
        @JsonProperty("approver_user_id") Long approverUserId,
        @JsonProperty("department_name") String departmentName,
        String description,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt,
        @JsonProperty("approved_at") LocalDateTime approvedAt,
        @JsonProperty("effective_at") LocalDateTime effectiveAt) {
}
