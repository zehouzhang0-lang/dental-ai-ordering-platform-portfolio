package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record ProductionEquipmentRequest(
        @JsonProperty("equipment_code") @NotBlank String equipmentCode,
        @JsonProperty("equipment_name") @NotBlank String equipmentName,
        @JsonProperty("equipment_type") @NotBlank String equipmentType,
        @JsonProperty("department_name") String departmentName,
        String status,
        @JsonProperty("utilization_rate") Double utilizationRate) {
}
