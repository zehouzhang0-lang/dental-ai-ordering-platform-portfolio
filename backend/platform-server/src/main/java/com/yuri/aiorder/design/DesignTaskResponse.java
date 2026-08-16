package com.yuri.aiorder.design;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.yuri.aiorder.collaboration.DesignDraftResponse;
import com.yuri.aiorder.collaboration.DesignDraftReviewEventResponse;
import java.time.LocalDateTime;
import java.util.List;

public record DesignTaskResponse(
        @JsonProperty("task_id") long taskId,
        @JsonProperty("order_id") long orderId,
        @JsonProperty("order_no") String orderNo,
        @JsonProperty("product_type") String productType,
        @JsonProperty("order_status") String orderStatus,
        String status,
        @JsonProperty("assigned_user_id")
        @JsonSerialize(using = ToStringSerializer.class)
        Long assignedUserId,
        @JsonProperty("assigned_user_name") String assignedUserName,
        @JsonProperty("claimed_at") LocalDateTime claimedAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt,
        @JsonProperty("latest_draft") DesignDraftResponse latestDraft,
        List<DesignDraftResponse> drafts,
        @JsonProperty("review_history") List<DesignDraftReviewEventResponse> reviewHistory,
        @JsonProperty("allowed_actions") List<String> allowedActions) {
}
