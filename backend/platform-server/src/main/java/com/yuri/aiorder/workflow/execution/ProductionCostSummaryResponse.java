package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record ProductionCostSummaryResponse(
        @JsonProperty("cost_no_prefix") String costNoPrefix,
        @JsonProperty("record_count") long recordCount,
        @JsonProperty("total_cost_amount") double totalCostAmount,
        @JsonProperty("process_cost_amount") double processCostAmount,
        @JsonProperty("material_cost_amount") double materialCostAmount,
        @JsonProperty("labor_cost_amount") double laborCostAmount,
        @JsonProperty("rework_cost_amount") double reworkCostAmount,
        @JsonProperty("outsourcing_cost_amount") double outsourcingCostAmount,
        @JsonProperty("abnormal_warning_count") long abnormalWarningCount,
        @JsonProperty("generated_at") LocalDateTime generatedAt) {
}
