package com.yuri.aiorder.notification;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.auth.BearerTokenService;
import com.yuri.aiorder.common.auth.DatabaseAuthService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class NotificationWebSocketAuthInterceptor implements HandshakeInterceptor {

    static final String USER_ID_ATTRIBUTE = "notificationUserId";

    private final BearerTokenService tokenService;
    private final DatabaseAuthService databaseAuthService;

    public NotificationWebSocketAuthInterceptor(
            BearerTokenService tokenService, DatabaseAuthService databaseAuthService) {
        this.tokenService = tokenService;
        this.databaseAuthService = databaseAuthService;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        String token = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("token");
        if (token == null || token.isBlank()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            BootstrapIdentity identity = tokenService.parse(token);
            if (identity.userId() == null) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            BootstrapIdentity activeIdentity = databaseAuthService.loadAuthenticatedUser(identity.userId()).identity();
            attributes.put(USER_ID_ATTRIBUTE, activeIdentity.userId());
            return true;
        } catch (RuntimeException ex) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
    }
}
