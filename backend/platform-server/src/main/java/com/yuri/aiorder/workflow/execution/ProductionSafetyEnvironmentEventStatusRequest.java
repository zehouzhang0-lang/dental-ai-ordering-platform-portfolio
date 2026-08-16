package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record ProductionSafetyEnvironmentEventStatusRequest(
        @JsonProperty("status") @NotBlank String status,
        @JsonProperty("responsible_owner") String responsibleOwner,
        String description) {
}
