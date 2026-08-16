package com.yuri.aiorder.workflow.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;

public record ProductionKanbanSummaryResponse(
        LocalDate date,
        @JsonProperty("visible_order_ids") List<Long> visibleOrderIds,
        @JsonProperty("stages") List<ProductionKanbanStageSummaryResponse> stages) {
}
