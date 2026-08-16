package com.yuri.aiorder.collaboration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.LocalDateTime;

public record DesignDraftReviewEventResponse(
        @JsonProperty("event_id") long eventId,
        @JsonProperty("draft_id") Long draftId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("actor_user_id")
        @JsonSerialize(using = ToStringSerializer.class)
        Long actorUserId,
        @JsonProperty("actor_role") String actorRole,
        @JsonProperty("from_status") String fromStatus,
        @JsonProperty("to_status") String toStatus,
        @JsonProperty("from_assignee_user_id")
        @JsonSerialize(using = ToStringSerializer.class)
        Long fromAssigneeUserId,
        @JsonProperty("to_assignee_user_id")
        @JsonSerialize(using = ToStringSerializer.class)
        Long toAssigneeUserId,
        String reason,
        @JsonProperty("created_at") LocalDateTime createdAt) {
}
