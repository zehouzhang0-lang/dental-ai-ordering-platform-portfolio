package com.yuri.aiorder.common.auth;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.ruoyi.RuoyiRuntimeBridge;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(RuoyiRuntimeBridge.BEARER_FILTER_ORDER)
public class BearerIdentityFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final BearerTokenService tokenService;
    private final DatabaseAuthService databaseAuthService;

    public BearerIdentityFilter(BearerTokenService tokenService, DatabaseAuthService databaseAuthService) {
        this.tokenService = tokenService;
        this.databaseAuthService = databaseAuthService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        try {
            if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
                BootstrapIdentity identity = tokenService.parse(authorization.substring(BEARER_PREFIX.length()));
                if (identity.userId() != null && identity.username() != null && !identity.username().isBlank()) {
                    identity = databaseAuthService.loadAuthenticatedUser(identity.userId()).identity();
                }
                IdentityContext.set(identity);
            }
            filterChain.doFilter(request, response);
        } catch (ResponseStatusException ex) {
            response.sendError(ex.getStatusCode().value(), ex.getReason());
        } finally {
            IdentityContext.clear();
        }
    }
}
