package com.yuri.aiorder.ai;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class AiExternalAlertSenderService {

    private static final String LOCAL_EXTERNAL_ALERT_CHANNEL = "EXTERNAL_ALERT";
    private static final int MAX_BATCH_SIZE = 100;
    private static final int MAX_ERROR_LENGTH = 512;

    private final JdbcClient jdbcClient;
    private final AiGatewayProperties properties;

    public AiExternalAlertSenderService(JdbcClient jdbcClient, AiGatewayProperties properties) {
        this.jdbcClient = jdbcClient;
        this.properties = properties;
    }

    @Transactional
    public int sendPendingAlerts(int batchSize) {
        int boundedBatchSize = Math.max(1, Math.min(batchSize, MAX_BATCH_SIZE));
        List<PendingAlert> pendingAlerts = jdbcClient.sql("""
                        SELECT alert_id, channel, CAST(payload AS CHAR) AS payload, attempts
                        FROM ai_external_alert_outbox
                        WHERE send_status = 'PENDING'
                        ORDER BY created_at, alert_id
                        LIMIT :batchSize
                        """)
                .param("batchSize", boundedBatchSize)
                .query((rs, rowNum) -> new PendingAlert(
                        rs.getLong("alert_id"),
                        rs.getString("channel"),
                        rs.getString("payload"),
                        rs.getInt("attempts")))
                .list();

        int sentCount = 0;
        for (PendingAlert pendingAlert : pendingAlerts) {
            if (!claimAlert(pendingAlert.alertId())) {
                continue;
            }
            if (LOCAL_EXTERNAL_ALERT_CHANNEL.equals(pendingAlert.channel())) {
                if (webhookEnabled()) {
                    if (!hasWebhookUrl()) {
                        markWebhookFailure(
                                pendingAlert.alertId(),
                                pendingAlert.attempts(),
                                "external alert webhook URL is required when webhook is enabled");
                        continue;
                    }
                    try {
                        sendWebhook(pendingAlert.payload());
                        sentCount += markSent(pendingAlert.alertId());
                    } catch (ExternalAlertSendException ex) {
                        markWebhookFailure(pendingAlert.alertId(), pendingAlert.attempts(), ex.getMessage());
                    }
                } else {
                    sentCount += markSent(pendingAlert.alertId());
                }
            } else {
                markFailed(pendingAlert.alertId(), "unsupported external alert channel: " + pendingAlert.channel());
            }
        }
        return sentCount;
    }

    private boolean claimAlert(long alertId) {
        return jdbcClient.sql("""
                        UPDATE ai_external_alert_outbox
                        SET send_status = 'SENDING'
                        WHERE alert_id = :alertId
                          AND send_status = 'PENDING'
                        """)
                .param("alertId", alertId)
                .update() == 1;
    }

    private boolean webhookEnabled() {
        return properties.getExternalAlert().isWebhookEnabled();
    }

    private boolean hasWebhookUrl() {
        String webhookUrl = properties.getExternalAlert().getWebhookUrl();
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    private void sendWebhook(String payload) {
        AiGatewayProperties.ExternalAlert externalAlert = properties.getExternalAlert();
        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory(externalAlert))
                .build();
        String requestBody = payload == null || payload.isBlank() ? "{}" : payload;
        try {
            restClient.post()
                    .uri(externalAlert.getWebhookUrl().trim())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> addWebhookSignature(headers, requestBody, externalAlert))
                    .body(requestBody)
                    .retrieve()
                    .toBodilessEntity();
        } catch (ExternalAlertSendException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            throw new ExternalAlertSendException(
                    "external alert webhook failed with status " + ex.getStatusCode().value(), ex);
        } catch (RuntimeException ex) {
            throw new ExternalAlertSendException(
                    "external alert webhook failed: " + ex.getClass().getSimpleName(), ex);
        }
    }

    private SimpleClientHttpRequestFactory requestFactory(AiGatewayProperties.ExternalAlert externalAlert) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.max(1, externalAlert.getConnectTimeoutSeconds())));
        factory.setReadTimeout(Duration.ofSeconds(Math.max(1, externalAlert.getReadTimeoutSeconds())));
        return factory;
    }

    private void addWebhookSignature(
            org.springframework.http.HttpHeaders headers,
            String payload,
            AiGatewayProperties.ExternalAlert externalAlert) {
        if (!externalAlert.isWebhookSigningEnabled()) {
            return;
        }
        String secret = externalAlert.getWebhookSigningSecret();
        if (secret == null || secret.isBlank()) {
            throw new ExternalAlertSendException("external alert webhook signing secret is required");
        }
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        headers.add("X-AI-Alert-Timestamp", timestamp);
        headers.add("X-AI-Alert-Nonce", nonce);
        headers.add("X-AI-Alert-Signature", webhookSignature(timestamp, nonce, payload, secret));
    }

    private String webhookSignature(String timestamp, String nonce, String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String base = timestamp + "." + nonce + "." + payload;
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(base.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new ExternalAlertSendException("external alert webhook signing failed", ex);
        }
    }

    private int markSent(long alertId) {
        return jdbcClient.sql("""
                        UPDATE ai_external_alert_outbox
                        SET send_status = 'SENT',
                            attempts = attempts + 1,
                            last_error = NULL
                        WHERE alert_id = :alertId
                          AND send_status = 'SENDING'
                        """)
                .param("alertId", alertId)
                .update();
    }

    private void markFailed(long alertId, String errorMessage) {
        jdbcClient.sql("""
                        UPDATE ai_external_alert_outbox
                        SET send_status = 'FAILED',
                            attempts = attempts + 1,
                            last_error = :lastError
                        WHERE alert_id = :alertId
                          AND send_status = 'SENDING'
                        """)
                .param("alertId", alertId)
                .param("lastError", truncateError(errorMessage))
                .update();
    }

    private void markWebhookFailure(long alertId, int currentAttempts, String errorMessage) {
        int nextAttempts = currentAttempts + 1;
        String nextStatus = nextAttempts >= maxAttempts() ? "DEAD_LETTER" : "PENDING";
        jdbcClient.sql("""
                        UPDATE ai_external_alert_outbox
                        SET send_status = :sendStatus,
                            attempts = attempts + 1,
                            last_error = :lastError
                        WHERE alert_id = :alertId
                          AND send_status = 'SENDING'
                        """)
                .param("alertId", alertId)
                .param("sendStatus", nextStatus)
                .param("lastError", truncateError(errorMessage))
                .update();
    }

    private int maxAttempts() {
        return Math.max(1, properties.getExternalAlert().getMaxAttempts());
    }

    private String truncateError(String errorMessage) {
        if (errorMessage == null || errorMessage.length() <= MAX_ERROR_LENGTH) {
            return errorMessage;
        }
        return errorMessage.substring(0, MAX_ERROR_LENGTH);
    }

    private record PendingAlert(long alertId, String channel, String payload, int attempts) {
    }

    private static class ExternalAlertSendException extends RuntimeException {
        private ExternalAlertSendException(String message) {
            super(message);
        }

        private ExternalAlertSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
