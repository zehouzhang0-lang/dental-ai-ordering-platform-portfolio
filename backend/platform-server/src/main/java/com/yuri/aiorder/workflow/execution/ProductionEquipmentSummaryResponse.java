package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record ProductionEquipmentSummaryResponse(
        @JsonProperty("equipment_code_prefix") String equipmentCodePrefix,
        @JsonProperty("total_equipment_count") long totalEquipmentCount,
        @JsonProperty("running_count") long runningCount,
        @JsonProperty("idle_count") long idleCount,
        @JsonProperty("maintenance_count") long maintenanceCount,
        @JsonProperty("fault_count") long faultCount,
        @JsonProperty("pending_maintenance_count") long pendingMaintenanceCount,
        @JsonProperty("open_fault_count") long openFaultCount,
        @JsonProperty("downtime_minutes") long downtimeMinutes,
        @JsonProperty("average_utilization_rate") double averageUtilizationRate,
        @JsonProperty("generated_at") LocalDateTime generatedAt) {
}
