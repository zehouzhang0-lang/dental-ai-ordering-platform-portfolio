package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record ProductionEquipmentEventRequest(
        @JsonProperty("event_type") @NotBlank String eventType,
        String status,
        @JsonProperty("downtime_minutes") Integer downtimeMinutes,
        String description) {
}
