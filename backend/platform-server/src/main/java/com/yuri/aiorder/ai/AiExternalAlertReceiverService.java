package com.yuri.aiorder.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AiExternalAlertReceiverService {

    private static final String SIGNATURE_PREFIX = "sha256=";
    private static final int MAX_NONCE_CACHE_SIZE = 2000;

    private final AiGatewayProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, Long> acceptedNonces = new ConcurrentHashMap<>();

    public AiExternalAlertReceiverService(AiGatewayProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public AiExternalAlertReceiverResponse receive(
            String payload,
            String timestampHeader,
            String nonce,
            String signature) {
        AiGatewayProperties.ExternalAlert externalAlert = properties.getExternalAlert();
        if (!externalAlert.isReceiverVerificationEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "AI external alert receiver verification is disabled");
        }
        String secret = externalAlert.getReceiverSigningSecret();
        if (secret == null || secret.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "AI external alert receiver signing secret is required");
        }
        long timestamp = parseTimestamp(timestampHeader);
        long now = Instant.now().getEpochSecond();
        long replayWindowSeconds = Math.max(1, externalAlert.getReceiverReplayWindowSeconds());
        purgeExpiredNonces(now, replayWindowSeconds);
        if (Math.abs(now - timestamp) > replayWindowSeconds) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AI external alert timestamp is outside window");
        }
        String normalizedNonce = requireHeader(nonce, "X-AI-Alert-Nonce");
        String normalizedSignature = requireHeader(signature, "X-AI-Alert-Signature");
        verifySignature(secret, timestampHeader, normalizedNonce, payload, normalizedSignature);
        Long existing = acceptedNonces.putIfAbsent(normalizedNonce, timestamp);
        if (existing != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AI external alert nonce was already accepted");
        }
        return new AiExternalAlertReceiverResponse(true, eventType(payload), normalizedNonce);
    }

    private long parseTimestamp(String timestampHeader) {
        String normalized = requireHeader(timestampHeader, "X-AI-Alert-Timestamp");
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AI external alert timestamp is invalid", ex);
        }
    }

    private String requireHeader(String value, String headerName) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, headerName + " is required");
        }
        return value.trim();
    }

    private void verifySignature(String secret, String timestamp, String nonce, String payload, String signature) {
        String expected = signature(secret, timestamp, nonce, payload == null ? "" : payload);
        if (!constantTimeEquals(expected, signature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AI external alert signature is invalid");
        }
    }

    private String signature(String secret, String timestamp, String nonce, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String base = timestamp + "." + nonce + "." + payload;
            return SIGNATURE_PREFIX + HexFormat.of().formatHex(mac.doFinal(base.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "AI external alert signature verification failed", ex);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private String eventType(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload == null || payload.isBlank() ? "{}" : payload);
            JsonNode event = root.get("event");
            if (event == null || event.asText().isBlank()) {
                return "UNKNOWN";
            }
            return event.asText();
        } catch (Exception ex) {
            return "UNKNOWN";
        }
    }

    private void purgeExpiredNonces(long now, long replayWindowSeconds) {
        acceptedNonces.entrySet().removeIf(entry -> Math.abs(now - entry.getValue()) > replayWindowSeconds);
        if (acceptedNonces.size() <= MAX_NONCE_CACHE_SIZE) {
            return;
        }
        acceptedNonces.clear();
    }
}
