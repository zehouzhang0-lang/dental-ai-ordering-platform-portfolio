package com.yuri.aiorder.common.auth;

import com.yuri.aiorder.common.BootstrapIdentity;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {

    public AuthConfiguration(AuthProperties properties) {
        BootstrapIdentity.setBootstrapHeadersAllowed(properties.allowBootstrapHeaders());
    }
}
