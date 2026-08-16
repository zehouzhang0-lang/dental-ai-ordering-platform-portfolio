package com.yuri.aiorder.common.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        String tokenSecret,
        long tokenTtlSeconds,
        long refreshTokenTtlSeconds,
        boolean allowBootstrapHeaders,
        boolean allowRoleFallback) {
}
