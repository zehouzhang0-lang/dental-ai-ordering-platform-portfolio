package com.yuri.aiorder.common.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yuri.aiorder.common.BootstrapIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

class AuthStartupValidatorTests {

    @AfterEach
    void tearDown() {
        BootstrapIdentity.setBootstrapHeadersAllowed(true);
    }

    @Test
    void prodProfileRejectsEnabledBootstrapHeaders() {
        AuthStartupValidator validator = new AuthStartupValidator(
                new AuthProperties("real-prod-secret", 7200, 2592000, true, true),
                environment("prod"));

        assertThatThrownBy(validator::validateAndApply)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_AUTH_ALLOW_BOOTSTRAP_HEADERS");
    }

    @Test
    void prodProfileRejectsLocalTokenSecret() {
        AuthStartupValidator validator = new AuthStartupValidator(
                new AuthProperties(AuthStartupValidator.LOCAL_TOKEN_SECRET, 7200, 2592000, false, false),
                environment("prod"));

        assertThatThrownBy(validator::validateAndApply)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_AUTH_TOKEN_SECRET");
    }

    @Test
    void prodProfileRejectsEnabledRoleFallback() {
        AuthStartupValidator validator = new AuthStartupValidator(
                new AuthProperties("real-prod-secret", 7200, 2592000, false, true),
                environment("prod"));

        assertThatThrownBy(validator::validateAndApply)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_AUTH_ALLOW_ROLE_FALLBACK");
    }

    @Test
    void nonProdProfileAppliesDisabledBootstrapHeaderFlag() {
        AuthStartupValidator validator = new AuthStartupValidator(
                new AuthProperties("local-test-secret", 7200, 2592000, false, true),
                environment("local"));

        validator.validateAndApply();

        assertThatThrownBy(() -> BootstrapIdentity.fromHeaders("ADMIN", 8001L, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("missing bearer token");
    }

    private MockEnvironment environment(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }
}
