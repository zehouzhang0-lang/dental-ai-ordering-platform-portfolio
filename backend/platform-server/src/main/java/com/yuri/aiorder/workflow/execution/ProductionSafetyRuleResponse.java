package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record ProductionSafetyRuleResponse(
        @JsonProperty("rule_id") long ruleId,
        @JsonProperty("rule_code") String ruleCode,
        @JsonProperty("rule_name") String ruleName,
        @JsonProperty("check_type") String checkType,
        @JsonProperty("department_name") String departmentName,
        @JsonProperty("cycle_type") String cycleType,
        @JsonProperty("cycle_interval") int cycleInterval,
        @JsonProperty("responsible_owner") String responsibleOwner,
        @JsonProperty("next_due_at") LocalDateTime nextDueAt,
        String status,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt) {
}
