package com.yuri.aiorder.notification;

public record NotificationBroadcastMessage(
        long userId,
        long eventId,
        String payload,
        String originInstanceId) {
}
