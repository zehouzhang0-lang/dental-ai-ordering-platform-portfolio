package com.yuri.aiorder.common.auth;

import com.yuri.aiorder.common.BootstrapIdentity;
import java.util.Arrays;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AuthStartupValidator implements ApplicationRunner {

    static final String LOCAL_TOKEN_SECRET = "local-dev-change-me-auth-secret";

    private final AuthProperties properties;
    private final Environment environment;

    public AuthStartupValidator(AuthProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        validateAndApply();
    }

    void validateAndApply() {
        if (isProdProfile()) {
            requireProductionAuthBoundary();
        }
        BootstrapIdentity.setBootstrapHeadersAllowed(properties.allowBootstrapHeaders());
    }

    private boolean isProdProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "prod".equalsIgnoreCase(profile));
    }

    private void requireProductionAuthBoundary() {
        if (properties.allowBootstrapHeaders()) {
            throw new IllegalStateException("APP_AUTH_ALLOW_BOOTSTRAP_HEADERS must be false in prod");
        }
        if (properties.allowRoleFallback()) {
            throw new IllegalStateException("APP_AUTH_ALLOW_ROLE_FALLBACK must be false in prod");
        }
        if (isBlank(properties.tokenSecret()) || LOCAL_TOKEN_SECRET.equals(properties.tokenSecret())) {
            throw new IllegalStateException("APP_AUTH_TOKEN_SECRET must be configured with a non-local secret in prod");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
