package com.yuri.aiorder.workflow.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ProductionKanbanStageSummaryResponse(
        @JsonProperty("stage_name") String stageName,
        @JsonProperty("unfinished_count") long unfinishedCount,
        @JsonProperty("in_progress_count") long inProgressCount,
        @JsonProperty("completed_count") long completedCount,
        @JsonProperty("overdue_count") long overdueCount,
        @JsonProperty("pending_question_count") long pendingQuestionCount,
        @JsonProperty("internal_rework_count") long internalReworkCount) {
}
