package com.yuri.aiorder.notification;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UnreadCountResponse(@JsonProperty("unread_count") long unreadCount) {
}
