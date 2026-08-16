package com.yuri.aiorder.workflow.execution;

import jakarta.validation.constraints.NotBlank;

public record ProductionCostStatusRequest(@NotBlank String status) {
}
