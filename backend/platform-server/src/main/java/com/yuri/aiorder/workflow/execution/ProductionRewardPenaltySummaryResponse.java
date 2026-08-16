package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record ProductionRewardPenaltySummaryResponse(
        @JsonProperty("record_no_prefix") String recordNoPrefix,
        @JsonProperty("total_record_count") long totalRecordCount,
        @JsonProperty("reward_count") long rewardCount,
        @JsonProperty("penalty_count") long penaltyCount,
        @JsonProperty("pending_count") long pendingCount,
        @JsonProperty("approved_count") long approvedCount,
        @JsonProperty("rejected_count") long rejectedCount,
        @JsonProperty("effective_count") long effectiveCount,
        @JsonProperty("related_order_count") long relatedOrderCount,
        @JsonProperty("related_process_count") long relatedProcessCount,
        @JsonProperty("related_employee_count") long relatedEmployeeCount,
        @JsonProperty("monthly_amount") double monthlyAmount,
        @JsonProperty("generated_at") LocalDateTime generatedAt) {
}
