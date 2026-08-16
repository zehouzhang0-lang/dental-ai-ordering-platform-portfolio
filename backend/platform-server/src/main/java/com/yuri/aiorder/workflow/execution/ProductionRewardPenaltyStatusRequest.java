package com.yuri.aiorder.workflow.execution;

import jakarta.validation.constraints.NotBlank;

public record ProductionRewardPenaltyStatusRequest(
        @NotBlank String status,
        String description) {
}
