package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record ProductionEquipmentApprovalRequest(
        @NotBlank String decision,
        @JsonProperty("decision_note") String decisionNote) {
}
