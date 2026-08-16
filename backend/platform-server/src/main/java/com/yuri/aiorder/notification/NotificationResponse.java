package com.yuri.aiorder.notification;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record NotificationResponse(
        @JsonProperty("notification_id") long notificationId,
        @JsonProperty("event_id") long eventId,
        String event,
        @JsonProperty("order_id") Long orderId,
        @JsonProperty("order_no") String orderNo,
        String message,
        @JsonProperty("read_at") LocalDateTime readAt,
        @JsonProperty("delivered_at") LocalDateTime deliveredAt,
        @JsonProperty("created_at") LocalDateTime createdAt) {
}
