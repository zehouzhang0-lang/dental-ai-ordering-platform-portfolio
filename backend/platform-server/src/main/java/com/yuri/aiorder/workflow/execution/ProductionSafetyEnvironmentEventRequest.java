package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

public record ProductionSafetyEnvironmentEventRequest(
        @JsonProperty("event_no") @NotBlank String eventNo,
        @JsonProperty("event_type") @NotBlank String eventType,
        String status,
        @JsonProperty("department_name") String departmentName,
        @JsonProperty("responsible_owner") String responsibleOwner,
        @JsonProperty("equipment_code") String equipmentCode,
        @JsonProperty("risk_level") String riskLevel,
        @JsonProperty("due_at") LocalDateTime dueAt,
        String description) {
}
