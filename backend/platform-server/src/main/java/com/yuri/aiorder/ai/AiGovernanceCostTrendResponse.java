package com.yuri.aiorder.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record AiGovernanceCostTrendResponse(
        int days,
        List<Point> points,
        @JsonProperty("total_success_count") long totalSuccessCount,
        @JsonProperty("total_estimated_cost_microusd") long totalEstimatedCostMicrousd) {

    public record Point(
            String date,
            @JsonProperty("success_count") long successCount,
            @JsonProperty("estimated_cost_microusd") long estimatedCostMicrousd,
            @JsonProperty("model_count") long modelCount) {
    }
}
