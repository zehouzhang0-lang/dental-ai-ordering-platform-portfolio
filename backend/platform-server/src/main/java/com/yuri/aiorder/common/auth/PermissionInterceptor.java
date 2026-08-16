package com.yuri.aiorder.common.auth;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private final AuthProperties properties;
    private final BootstrapIdentityFactory identityFactory;

    public PermissionInterceptor(AuthProperties properties, BootstrapIdentityFactory identityFactory) {
        this.properties = properties;
        this.identityFactory = identityFactory;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequirePermission requirement = findRequirement(handlerMethod);
        if (requirement == null) {
            return true;
        }
        BootstrapIdentity identity = currentIdentity(request);
        if (hasRequiredPermission(identity, requirement) || hasFallbackRole(identity, requirement)) {
            return true;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing required permission");
    }

    private RequirePermission findRequirement(HandlerMethod handlerMethod) {
        RequirePermission methodRequirement = handlerMethod.getMethodAnnotation(RequirePermission.class);
        if (methodRequirement != null) {
            return methodRequirement;
        }
        return handlerMethod.getBeanType().getAnnotation(RequirePermission.class);
    }

    private BootstrapIdentity currentIdentity(HttpServletRequest request) {
        BootstrapIdentity identity = IdentityContext.current();
        if (identity != null) {
            return identity;
        }
        return identityFactory.resolve(
                request.getHeader("X-Bootstrap-Role"),
                parseLongHeader(request.getHeader("X-Bootstrap-User-Id")),
                parseLongHeader(request.getHeader("X-Bootstrap-Clinic-Id")));
    }

    private Long parseLongHeader(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid bootstrap identity header", ex);
        }
    }

    private boolean hasRequiredPermission(BootstrapIdentity identity, RequirePermission requirement) {
        return Arrays.stream(requirement.value())
                .anyMatch(identity::hasPermission);
    }

    private boolean hasFallbackRole(BootstrapIdentity identity, RequirePermission requirement) {
        if (requirement.value().length > 0 && !properties.allowRoleFallback()) {
            return false;
        }
        Set<UserRole> allowedRoles = Arrays.stream(requirement.roles()).collect(Collectors.toSet());
        return allowedRoles.contains(identity.role());
    }
}
