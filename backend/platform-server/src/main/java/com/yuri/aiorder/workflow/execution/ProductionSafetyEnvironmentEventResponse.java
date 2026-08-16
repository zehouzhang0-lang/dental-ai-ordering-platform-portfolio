package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record ProductionSafetyEnvironmentEventResponse(
        @JsonProperty("event_id") long eventId,
        @JsonProperty("event_no") String eventNo,
        @JsonProperty("event_type") String eventType,
        String status,
        @JsonProperty("department_name") String departmentName,
        @JsonProperty("responsible_owner") String responsibleOwner,
        @JsonProperty("equipment_code") String equipmentCode,
        @JsonProperty("risk_level") String riskLevel,
        @JsonProperty("due_at") LocalDateTime dueAt,
        String description,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt,
        @JsonProperty("closed_at") LocalDateTime closedAt) {
}
