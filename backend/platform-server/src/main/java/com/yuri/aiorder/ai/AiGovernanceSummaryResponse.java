package com.yuri.aiorder.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record AiGovernanceSummaryResponse(
        @JsonProperty("window_hours") int windowHours,
        @JsonProperty("success_count") long successCount,
        @JsonProperty("safe_refusal_count") long safeRefusalCount,
        @JsonProperty("rate_limited_count") long rateLimitedCount,
        @JsonProperty("model_failed_count") long modelFailedCount,
        @JsonProperty("estimated_cost_microusd") long estimatedCostMicrousd,
        @JsonProperty("daily_budget_microusd") long dailyBudgetMicrousd,
        @JsonProperty("budget_exceeded") boolean budgetExceeded,
        @JsonProperty("budget_alert_count") long budgetAlertCount,
        @JsonProperty("latest_model_failure_at") LocalDateTime latestModelFailureAt,
        @JsonProperty("latest_budget_alert_at") LocalDateTime latestBudgetAlertAt) {
}
