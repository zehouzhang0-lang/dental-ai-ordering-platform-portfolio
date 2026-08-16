package com.yuri.aiorder.common.auth;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;

@Configuration
public class PermissionWebConfiguration implements WebMvcConfigurer {

    private final PermissionInterceptor permissionInterceptor;
    private final BootstrapIdentityArgumentResolver bootstrapIdentityArgumentResolver;

    public PermissionWebConfiguration(
            PermissionInterceptor permissionInterceptor,
            BootstrapIdentityArgumentResolver bootstrapIdentityArgumentResolver) {
        this.permissionInterceptor = permissionInterceptor;
        this.bootstrapIdentityArgumentResolver = bootstrapIdentityArgumentResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(bootstrapIdentityArgumentResolver);
    }
}
