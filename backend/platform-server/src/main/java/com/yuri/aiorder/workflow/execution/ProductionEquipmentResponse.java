package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record ProductionEquipmentResponse(
        @JsonProperty("equipment_id") long equipmentId,
        @JsonProperty("equipment_code") String equipmentCode,
        @JsonProperty("equipment_name") String equipmentName,
        @JsonProperty("equipment_type") String equipmentType,
        @JsonProperty("department_name") String departmentName,
        String status,
        @JsonProperty("owner_user_id") Long ownerUserId,
        @JsonProperty("utilization_rate") double utilizationRate,
        @JsonProperty("last_maintenance_at") LocalDateTime lastMaintenanceAt,
        @JsonProperty("next_maintenance_at") LocalDateTime nextMaintenanceAt,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt) {
}
