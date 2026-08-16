package com.yuri.aiorder.common.auth;

import com.yuri.aiorder.common.BootstrapIdentity;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

@Component
public class BootstrapIdentityArgumentResolver implements HandlerMethodArgumentResolver {

    private final BootstrapIdentityFactory identityFactory;

    public BootstrapIdentityArgumentResolver(BootstrapIdentityFactory identityFactory) {
        this.identityFactory = identityFactory;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return BootstrapIdentity.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        return identityFactory.resolve(
                webRequest.getHeader("X-Bootstrap-Role"),
                parseLongHeader(webRequest.getHeader("X-Bootstrap-User-Id")),
                parseLongHeader(webRequest.getHeader("X-Bootstrap-Clinic-Id")));
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
}
