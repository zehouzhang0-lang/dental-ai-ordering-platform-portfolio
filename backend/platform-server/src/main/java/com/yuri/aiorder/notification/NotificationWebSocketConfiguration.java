package com.yuri.aiorder.notification;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class NotificationWebSocketConfiguration implements WebSocketConfigurer {

    private final NotificationWebSocketHandler handler;
    private final NotificationWebSocketAuthInterceptor authInterceptor;
    private final List<String> allowedOrigins;

    public NotificationWebSocketConfiguration(
            NotificationWebSocketHandler handler,
            NotificationWebSocketAuthInterceptor authInterceptor,
            @Value("${app.cors.allowed-origin:http://localhost:5173,http://127.0.0.1:5173}") List<String> allowedOrigins) {
        this.handler = handler;
        this.authInterceptor = authInterceptor;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/connect")
                .addInterceptors(authInterceptor)
                .setAllowedOriginPatterns(allowedOrigins.stream()
                        .map(String::trim)
                        .filter(origin -> !origin.isEmpty())
                        .toArray(String[]::new));
    }
}
