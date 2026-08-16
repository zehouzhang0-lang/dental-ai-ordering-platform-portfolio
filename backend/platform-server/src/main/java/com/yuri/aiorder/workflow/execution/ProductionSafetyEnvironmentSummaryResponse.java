package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record ProductionSafetyEnvironmentSummaryResponse(
        @JsonProperty("event_no_prefix") String eventNoPrefix,
        @JsonProperty("total_event_count") long totalEventCount,
        @JsonProperty("safety_inspection_count") long safetyInspectionCount,
        @JsonProperty("hazard_rectification_count") long hazardRectificationCount,
        @JsonProperty("environment_record_count") long environmentRecordCount,
        @JsonProperty("ppe_device_reminder_count") long ppeDeviceReminderCount,
        @JsonProperty("pending_count") long pendingCount,
        @JsonProperty("in_progress_count") long inProgressCount,
        @JsonProperty("closed_count") long closedCount,
        @JsonProperty("overdue_count") long overdueCount,
        @JsonProperty("high_risk_count") long highRiskCount,
        @JsonProperty("generated_at") LocalDateTime generatedAt) {
}
