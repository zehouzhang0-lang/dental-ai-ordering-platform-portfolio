package com.yuri.aiorder.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.notification.redis-broadcast-enabled", havingValue = "true")
public class NotificationRedisBroadcastListener {

    private final ObjectMapper objectMapper;
    private final NotificationPushService pushService;
    private final String instanceId;

    public NotificationRedisBroadcastListener(
            ObjectMapper objectMapper,
            NotificationPushService pushService,
            @Value("${app.notification.instance-id}") String instanceId) {
        this.objectMapper = objectMapper;
        this.pushService = pushService;
        this.instanceId = instanceId;
    }

    public void handleMessage(String message) {
        NotificationBroadcastMessage broadcast = readMessage(message);
        if (broadcast == null || instanceId.equals(broadcast.originInstanceId())) {
            return;
        }
        pushService.pushLocalToUser(broadcast.userId(), broadcast.eventId(), broadcast.payload());
    }

    private NotificationBroadcastMessage readMessage(String message) {
        try {
            return objectMapper.readValue(message, NotificationBroadcastMessage.class);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }
}
