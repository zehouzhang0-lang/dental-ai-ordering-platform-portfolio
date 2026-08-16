package com.yuri.aiorder.rbac;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record RbacAuditResponse(
        @JsonProperty("audit_id") long auditId,
        @JsonProperty("entity_type") String entityType,
        @JsonProperty("entity_id") Long entityId,
        @JsonProperty("entity_label") String entityLabel,
        @JsonProperty("action_type") String actionType,
        @JsonProperty("before_value") String beforeValue,
        @JsonProperty("after_value") String afterValue,
        @JsonProperty("operator_user_id") Long operatorUserId,
        @JsonProperty("operator_username") String operatorUsername,
        String reason,
        @JsonProperty("created_at") LocalDateTime createdAt) {
}
