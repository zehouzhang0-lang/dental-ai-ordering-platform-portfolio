package com.yuri.aiorder.collaboration;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record MessageAttentionItemResponse(
        @JsonProperty("message_id") long messageId,
        @JsonProperty("order_id") long orderId,
        @JsonProperty("order_no") String orderNo,
        @JsonProperty("sender_user_id") Long senderUserId,
        @JsonProperty("sender_role") String senderRole,
        String content,
        @JsonProperty("mention_user_id") long mentionUserId,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("resolved_at") LocalDateTime resolvedAt) {
}
