package com.yuri.aiorder.notification;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@ConditionalOnProperty(name = "app.notification.redis-broadcast-enabled", havingValue = "true")
public class NotificationRedisBroadcastConfiguration {

    @Bean
    ChannelTopic notificationBroadcastTopic(
            @Value("${app.notification.redis-channel:ai-order:notifications}") String channel) {
        return new ChannelTopic(channel);
    }

    @Bean
    RedisMessageListenerContainer notificationRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            ChannelTopic notificationBroadcastTopic,
            NotificationRedisBroadcastListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) ->
                        listener.handleMessage(new String(message.getBody(), StandardCharsets.UTF_8)),
                notificationBroadcastTopic);
        return container;
    }
}
