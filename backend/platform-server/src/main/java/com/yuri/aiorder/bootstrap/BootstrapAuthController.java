package com.yuri.aiorder.bootstrap;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.auth.AuthMenu;
import com.yuri.aiorder.common.auth.AuthenticatedUser;
import com.yuri.aiorder.common.auth.BearerTokenService;
import com.yuri.aiorder.common.auth.DatabaseAuthService;
import com.yuri.aiorder.common.auth.RefreshTokenService;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "${app.cors.allowed-origin:http://localhost:5173,http://127.0.0.1:5173}", allowCredentials = "true")
public class BootstrapAuthController {

    private static final String REFRESH_COOKIE_NAME = "AI_ORDER_REFRESH";

    private final BearerTokenService tokenService;
    private final DatabaseAuthService databaseAuthService;
    private final RefreshTokenService refreshTokenService;

    public BootstrapAuthController(
            BearerTokenService tokenService,
            DatabaseAuthService databaseAuthService,
            RefreshTokenService refreshTokenService) {
        this.tokenService = tokenService;
        this.databaseAuthService = databaseAuthService;
        this.refreshTokenService = refreshTokenService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        AuthenticatedUser authenticatedUser = databaseAuthService.authenticate(request.username(), request.password());
        requirePortalRole(request.portal(), authenticatedUser.roles());
        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokenService.issue(authenticatedUser.userId());
        return responseWithRefreshCookie(
                loginResponse(authenticatedUser, refreshToken.token(), refreshToken.expiresAt()),
                refreshToken.token(),
                refreshToken.expiresAt(),
                httpRequest);
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @Valid @RequestBody(required = false) RefreshTokenRequest request,
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String cookieRefreshToken,
            HttpServletRequest httpRequest) {
        String requestedRefreshToken = resolveRefreshToken(request, cookieRefreshToken);
        RefreshTokenService.IssuedRefreshToken refreshToken =
                refreshTokenService.rotate(requestedRefreshToken);
        AuthenticatedUser authenticatedUser = databaseAuthService.loadAuthenticatedUser(refreshToken.userId());
        return responseWithRefreshCookie(
                loginResponse(authenticatedUser, refreshToken.token(), refreshToken.expiresAt()),
                refreshToken.token(),
                refreshToken.expiresAt(),
                httpRequest);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @Valid @RequestBody(required = false) RefreshTokenRequest request,
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String cookieRefreshToken,
            HttpServletRequest httpRequest) {
        String requestedRefreshToken = optionalRefreshToken(request, cookieRefreshToken);
        if (requestedRefreshToken != null) {
            refreshTokenService.revoke(requestedRefreshToken);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearRefreshCookie(httpRequest).toString())
                .build();
    }

    private ResponseEntity<LoginResponse> responseWithRefreshCookie(
            LoginResponse response,
            String refreshToken,
            Instant refreshExpiresAt,
            HttpServletRequest httpRequest) {
        Duration maxAge = Duration.between(Instant.now(), refreshExpiresAt);
        if (maxAge.isNegative()) {
            maxAge = Duration.ZERO;
        }
        ResponseCookie cookie = refreshCookie(refreshToken, maxAge, httpRequest);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(response);
    }

    private String resolveRefreshToken(RefreshTokenRequest request, String cookieRefreshToken) {
        String refreshToken = optionalRefreshToken(request, cookieRefreshToken);
        if (refreshToken == null) {
            throw new UnauthorizedException();
        }
        return refreshToken;
    }

    private String optionalRefreshToken(RefreshTokenRequest request, String cookieRefreshToken) {
        if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
            return request.refreshToken();
        }
        if (cookieRefreshToken != null && !cookieRefreshToken.isBlank()) {
            return cookieRefreshToken;
        }
        return null;
    }

    private ResponseCookie refreshCookie(String value, Duration maxAge, HttpServletRequest httpRequest) {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, value)
                .httpOnly(true)
                .secure(isSecureRequest(httpRequest))
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(maxAge)
                .build();
    }

    private ResponseCookie clearRefreshCookie(HttpServletRequest httpRequest) {
        return refreshCookie("", Duration.ZERO, httpRequest);
    }

    private boolean isSecureRequest(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        return forwardedProto != null
                && forwardedProto.split(",", 2)[0].trim().equalsIgnoreCase("https");
    }

    private LoginResponse loginResponse(AuthenticatedUser authenticatedUser, String refreshToken, Instant refreshExpiresAt) {
        return new LoginResponse(
                tokenService.issue(authenticatedUser.identity()),
                refreshToken,
                authenticatedUser.username(),
                authenticatedUser.userId(),
                authenticatedUser.clinicId(),
                authenticatedUser.roles(),
                authenticatedUser.permissions(),
                authenticatedUser.menus(),
                authenticatedUser.dataScope(),
                Instant.now().plusSeconds(tokenService.tokenTtlSeconds()),
                refreshExpiresAt);
    }

    private void requirePortalRole(LoginPortal portal, List<String> roles) {
        String requiredRole = switch (portal) {
            case DOCTOR -> "DOCTOR";
            case CS -> "CS";
            case PRODUCTION -> "WORKER";
            case ADMIN -> "ADMIN";
        };
        if (!roles.contains(requiredRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "account role does not match login portal");
        }
    }

    @GetMapping("/me")
    public CurrentUserResponse me(@RequestHeader(name = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new UnauthorizedException();
        }
        BootstrapIdentity identity = tokenService.parse(authorization.substring("Bearer ".length()));
        if (identity.userId() != null && identity.username() != null && !identity.username().isBlank()) {
            AuthenticatedUser authenticatedUser = databaseAuthService.loadAuthenticatedUser(identity.userId());
            return new CurrentUserResponse(
                    authenticatedUser.username(),
                    authenticatedUser.userId(),
                    authenticatedUser.clinicId(),
                    authenticatedUser.roles(),
                    authenticatedUser.permissions(),
                    authenticatedUser.menus(),
                    authenticatedUser.dataScope());
        }
        return new CurrentUserResponse(
                identity.username() == null ? identity.role().name() : identity.username(),
                identity.userId(),
                identity.clinicId(),
                List.of(identity.role().name()),
                identity.permissions().stream().sorted().toList(),
                databaseAuthService.loadMenus(identity),
                identity.dataScope());
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password, @NotNull LoginPortal portal) {
    }

    public enum LoginPortal {
        DOCTOR,
        CS,
        PRODUCTION,
        ADMIN
    }

    public record RefreshTokenRequest(@JsonProperty("refresh_token") @NotBlank String refreshToken) {
    }

    public record LoginResponse(
            String accessToken,
            String refreshToken,
            String username,
            @JsonSerialize(using = ToStringSerializer.class) Long userId,
            Long clinicId,
            List<String> roles,
            List<String> permissions,
            List<AuthMenu> menus,
            String dataScope,
            Instant expiresAt,
            Instant refreshExpiresAt) {
    }

    public record CurrentUserResponse(
            String username,
            @JsonSerialize(using = ToStringSerializer.class) Long userId,
            Long clinicId,
            List<String> roles,
            List<String> permissions,
            List<AuthMenu> menus,
            String dataScope) {
    }

    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    private static final class UnauthorizedException extends RuntimeException {
    }
}
