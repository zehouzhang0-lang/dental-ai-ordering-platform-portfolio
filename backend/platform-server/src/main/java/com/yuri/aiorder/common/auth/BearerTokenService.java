package com.yuri.aiorder.common.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BearerTokenService {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final AuthProperties properties;

    public BearerTokenService(ObjectMapper objectMapper, AuthProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public String issue(BootstrapIdentity identity) {
        long expiresAt = Instant.now().plusSeconds(properties.tokenTtlSeconds()).getEpochSecond();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("role", identity.role().name());
        if (identity.username() != null) {
            payload.put("username", identity.username());
        }
        if (identity.userId() != null) {
            payload.put("user_id", identity.userId());
        }
        if (identity.clinicId() != null) {
            payload.put("clinic_id", identity.clinicId());
        }
        if (identity.dataScope() != null) {
            payload.put("data_scope", identity.dataScope());
        }
        ArrayNode permissions = payload.putArray("permissions");
        identity.permissions().stream()
                .sorted()
                .forEach(permissions::add);
        payload.put("exp", expiresAt);
        return encode(payload);
    }

    public BootstrapIdentity parse(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw unauthorized("invalid bearer token");
        }
        String expectedSignature = sign(parts[0]);
        if (!MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                parts[1].getBytes(StandardCharsets.UTF_8))) {
            throw unauthorized("invalid bearer token signature");
        }
        try {
            JsonNode payload = objectMapper.readTree(URL_DECODER.decode(parts[0]));
            long expiresAt = payload.path("exp").asLong(0);
            if (expiresAt <= Instant.now().getEpochSecond()) {
                throw unauthorized("bearer token expired");
            }
            UserRole role = UserRole.valueOf(payload.path("role").asText());
            String username = payload.hasNonNull("username") ? payload.path("username").asText() : null;
            Long userId = payload.hasNonNull("user_id") ? payload.path("user_id").asLong() : null;
            Long clinicId = payload.hasNonNull("clinic_id") ? payload.path("clinic_id").asLong() : null;
            String dataScope = payload.hasNonNull("data_scope") ? payload.path("data_scope").asText() : null;
            Set<String> permissions = payload.path("permissions").isArray()
                    ? StreamSupport.stream(payload.path("permissions").spliterator(), false)
                            .map(JsonNode::asText)
                            .filter(value -> value != null && !value.isBlank())
                            .collect(Collectors.toSet())
                    : Set.of();
            return new BootstrapIdentity(role, userId, clinicId, username, permissions, dataScope);
        } catch (IllegalArgumentException ex) {
            throw unauthorized("invalid bearer token role");
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unauthorized("invalid bearer token payload");
        }
    }

    public long tokenTtlSeconds() {
        return properties.tokenTtlSeconds();
    }

    private String encode(ObjectNode payload) {
        try {
            String encodedPayload = URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(payload));
            return encodedPayload + "." + sign(encodedPayload);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "cannot issue bearer token", ex);
        }
    }

    private String sign(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.tokenSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return URL_ENCODER.encodeToString(mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "cannot sign bearer token", ex);
        }
    }

    private ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }
}
