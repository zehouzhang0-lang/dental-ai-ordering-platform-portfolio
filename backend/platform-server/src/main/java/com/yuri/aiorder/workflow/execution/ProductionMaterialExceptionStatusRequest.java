package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record ProductionMaterialExceptionStatusRequest(
        @JsonProperty("status") @NotBlank String status,
        @JsonProperty("responsibility_owner") String responsibilityOwner,
        String description) {
}
