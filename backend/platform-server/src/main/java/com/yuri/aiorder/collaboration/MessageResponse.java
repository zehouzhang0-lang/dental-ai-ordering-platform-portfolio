package com.yuri.aiorder.collaboration;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;

public record MessageResponse(
        @JsonProperty("msg_id") long msgId,
        @JsonProperty("order_id") long orderId,
        @JsonProperty("order_no") String orderNo,
        @JsonProperty("product_type") String productType,
        @JsonProperty("external_status") String externalStatus,
        @JsonProperty("sender_user_id") Long senderUserId,
        @JsonProperty("sender_role") String senderRole,
        String content,
        @JsonProperty("visible_to") String visibleTo,
        @JsonProperty("review_status") String reviewStatus,
        @JsonProperty("mention_user_ids") List<Long> mentionUserIds,
        @JsonProperty("created_at") LocalDateTime createdAt) {
}
