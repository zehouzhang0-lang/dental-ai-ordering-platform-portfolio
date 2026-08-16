package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record ProductionMaterialExceptionSummaryResponse(
        @JsonProperty("exception_no_prefix") String exceptionNoPrefix,
        @JsonProperty("total_exception_count") long totalExceptionCount,
        @JsonProperty("current_month_count") long currentMonthCount,
        @JsonProperty("previous_month_count") long previousMonthCount,
        @JsonProperty("shortage_count") long shortageCount,
        @JsonProperty("wrong_material_count") long wrongMaterialCount,
        @JsonProperty("batch_abnormal_count") long batchAbnormalCount,
        @JsonProperty("material_loss_count") long materialLossCount,
        @JsonProperty("pending_count") long pendingCount,
        @JsonProperty("in_progress_count") long inProgressCount,
        @JsonProperty("closed_count") long closedCount,
        @JsonProperty("responsibility_assigned_count") long responsibilityAssignedCount,
        @JsonProperty("total_loss_quantity") double totalLossQuantity,
        @JsonProperty("generated_at") LocalDateTime generatedAt) {
}
