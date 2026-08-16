package com.yuri.aiorder.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.notification.redis-broadcast-enabled", havingValue = "true")
public class NotificationRedisBroadcaster implements NotificationBroadcaster {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String channel;

    public NotificationRedisBroadcaster(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${app.notification.redis-channel:ai-order:notifications}") String channel) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.channel = channel;
    }

    @Override
    public void broadcast(NotificationBroadcastMessage message) {
        try {
            redisTemplate.convertAndSend(channel, objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize notification broadcast", ex);
        }
    }
}
