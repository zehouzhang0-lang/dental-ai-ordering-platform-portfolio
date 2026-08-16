package com.yuri.aiorder.common.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RefreshTokenService {

    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final JdbcClient jdbcClient;
    private final AuthProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(JdbcClient jdbcClient, AuthProperties properties) {
        this.jdbcClient = jdbcClient;
        this.properties = properties;
    }

    @Transactional
    public IssuedRefreshToken issue(long userId) {
        return issue(userId, UUID.randomUUID().toString());
    }

    private IssuedRefreshToken issue(long userId, String familyId) {
        String token = randomToken();
        Instant expiresAt = Instant.now().plusSeconds(properties.refreshTokenTtlSeconds());
        jdbcClient.sql("""
                        INSERT INTO auth_refresh_token
                            (family_id, token_hash, user_id, expires_at)
                        VALUES
                            (:familyId, :tokenHash, :userId, :expiresAt)
                        """)
                .param("familyId", familyId)
                .param("tokenHash", hash(token))
                .param("userId", userId)
                .param("expiresAt", expiresAt)
                .update();
        return new IssuedRefreshToken(token, expiresAt, userId);
    }

    @Transactional
    public ActiveRefreshToken requireActive(String refreshToken) {
        String tokenHash = hash(refreshToken);
        RefreshTokenRow row = requireActiveByHash(tokenHash);
        jdbcClient.sql("""
                        UPDATE auth_refresh_token
                        SET last_used_at = CURRENT_TIMESTAMP(3)
                        WHERE token_id = :tokenId
                        """)
                .param("tokenId", row.tokenId())
                .update();
        return new ActiveRefreshToken(row.userId(), row.expiresAt());
    }

    @Transactional
    public IssuedRefreshToken rotate(String refreshToken) {
        String tokenHash = hash(refreshToken);
        RefreshTokenRow row = requireActiveByHash(tokenHash);
        if (revokeByHash(tokenHash) != 1) {
            throw unauthorized();
        }
        return issue(row.userId(), row.familyId());
    }

    private RefreshTokenRow requireActiveByHash(String tokenHash) {
        RefreshTokenRow row = jdbcClient.sql("""
                        SELECT token_id, family_id, user_id, expires_at, revoked_at
                        FROM auth_refresh_token
                        WHERE token_hash = :tokenHash
                        """)
                .param("tokenHash", tokenHash)
                .query((rs, rowNum) -> new RefreshTokenRow(
                        rs.getLong("token_id"),
                        rs.getString("family_id"),
                        rs.getLong("user_id"),
                        rs.getTimestamp("expires_at").toInstant(),
                        toInstant(rs.getTimestamp("revoked_at"))))
                .optional()
                .orElseThrow(this::unauthorized);
        if (row.revokedAt() != null || !row.expiresAt().isAfter(Instant.now())) {
            throw unauthorized();
        }
        return row;
    }

    @Transactional
    public void revoke(String refreshToken) {
        String tokenHash = hash(refreshToken);
        String familyId = jdbcClient.sql("""
                        SELECT family_id
                        FROM auth_refresh_token
                        WHERE token_hash = :tokenHash
                        FOR UPDATE
                        """)
                .param("tokenHash", tokenHash)
                .query(String.class)
                .optional()
                .orElse(null);
        if (familyId == null) {
            return;
        }
        jdbcClient.sql("""
                        UPDATE auth_refresh_token
                        SET revoked_at = CURRENT_TIMESTAMP(3)
                        WHERE family_id = :familyId
                          AND revoked_at IS NULL
                        """)
                .param("familyId", familyId)
                .update();
    }

    private int revokeByHash(String tokenHash) {
        return jdbcClient.sql("""
                        UPDATE auth_refresh_token
                        SET revoked_at = CURRENT_TIMESTAMP(3)
                        WHERE token_hash = :tokenHash
                          AND revoked_at IS NULL
                        """)
                .param("tokenHash", tokenHash)
                .update();
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return TOKEN_ENCODER.encodeToString(bytes);
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String hash(String token) {
        if (token == null || token.isBlank()) {
            throw unauthorized();
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "cannot hash refresh token", ex);
        }
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid refresh token");
    }

    public record IssuedRefreshToken(String token, Instant expiresAt, long userId) {
    }

    public record ActiveRefreshToken(long userId, Instant expiresAt) {
    }

    private record RefreshTokenRow(
            long tokenId,
            String familyId,
            long userId,
            Instant expiresAt,
            Instant revokedAt) {
    }
}
