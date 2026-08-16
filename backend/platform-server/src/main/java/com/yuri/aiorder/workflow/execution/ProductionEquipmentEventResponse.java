package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record ProductionEquipmentEventResponse(
        @JsonProperty("event_id") long eventId,
        @JsonProperty("equipment_id") long equipmentId,
        @JsonProperty("equipment_code") String equipmentCode,
        @JsonProperty("event_type") String eventType,
        String status,
        @JsonProperty("downtime_minutes") int downtimeMinutes,
        String description,
        @JsonProperty("requested_by_user_id") Long requestedByUserId,
        @JsonProperty("approved_by_user_id") Long approvedByUserId,
        @JsonProperty("decision_note") String decisionNote,
        @JsonProperty("decided_at") LocalDateTime decidedAt,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("resolved_at") LocalDateTime resolvedAt) {
}
