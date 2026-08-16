package com.yuri.aiorder.notification;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class NotificationWebSocketHandler extends TextWebSocketHandler {

    private final NotificationPushService pushService;

    public NotificationWebSocketHandler(NotificationPushService pushService) {
        this.pushService = pushService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long userId = currentUserId(session);
        if (userId == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        pushService.register(userId, session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = currentUserId(session);
        if (userId != null) {
            pushService.unregister(userId, session);
        }
    }

    private Long currentUserId(WebSocketSession session) {
        Object userId = session.getAttributes().get(NotificationWebSocketAuthInterceptor.USER_ID_ATTRIBUTE);
        return userId instanceof Long value ? value : null;
    }
}
