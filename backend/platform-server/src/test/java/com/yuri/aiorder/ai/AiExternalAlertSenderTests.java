package com.yuri.aiorder.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class AiExternalAlertSenderTests {

    private static ExternalAlertWebhookStub webhookStub;

    @BeforeAll
    static void startWebhookStub() {
        webhookStub = new ExternalAlertWebhookStub();
    }

    @AfterAll
    static void stopWebhookStub() {
        webhookStub.stop();
    }

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private AiExternalAlertSenderService senderService;

    @Autowired
    private AiExternalAlertScheduler scheduler;

    @Autowired
    private AiGatewayProperties aiGatewayProperties;

    private long orderId;

    @BeforeEach
    void setUp() {
        closeStaleSenderTestAlerts();
        webhookStub.reset();
        aiGatewayProperties.getExternalAlert().setWebhookEnabled(false);
        aiGatewayProperties.getExternalAlert().setWebhookUrl("");
        aiGatewayProperties.getExternalAlert().setConnectTimeoutSeconds(2);
        aiGatewayProperties.getExternalAlert().setReadTimeoutSeconds(2);
        aiGatewayProperties.getExternalAlert().setSchedulerEnabled(false);
        aiGatewayProperties.getExternalAlert().setSchedulerBatchSize(10);
        aiGatewayProperties.getExternalAlert().setMaxAttempts(3);
        aiGatewayProperties.getExternalAlert().setWebhookSigningEnabled(false);
        aiGatewayProperties.getExternalAlert().setWebhookSigningSecret("");

        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbcClient.sql("INSERT INTO clinic (clinic_name) VALUES (:clinicName)")
                .param("clinicName", "外部告警发送器测试诊所-" + suffix)
                .update();
        long clinicId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();

        jdbcClient.sql("""
                        INSERT INTO orders
                            (order_no, clinic_id, doctor_user_id, cs_user_id, product_type,
                             form_data, internal_status, external_status)
                        VALUES
                            (:orderNo, :clinicId, 9911, 9912, :productType,
                             JSON_OBJECT('patient_name', '王五'), 'IN_PRODUCTION', 'PRODUCING')
                        """)
                .param("orderNo", "AIALERT" + suffix.substring(0, 9))
                .param("clinicId", clinicId)
                .param("productType", "AI_ALERT_" + suffix.substring(0, 10))
                .update();
        orderId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    @AfterEach
    void tearDown() {
        closeCurrentSenderTestAlerts();
        aiGatewayProperties.getExternalAlert().setWebhookEnabled(false);
        aiGatewayProperties.getExternalAlert().setWebhookUrl("");
        aiGatewayProperties.getExternalAlert().setSchedulerEnabled(false);
        aiGatewayProperties.getExternalAlert().setMaxAttempts(3);
        aiGatewayProperties.getExternalAlert().setWebhookSigningEnabled(false);
        aiGatewayProperties.getExternalAlert().setWebhookSigningSecret("");
    }

    @Test
    void senderMarksPendingExternalAlertAsSentWithoutRealChannel() {
        long alertId = insertPendingAlert("EXTERNAL_ALERT");

        int sent = senderService.sendPendingAlerts(10);

        assertThat(sent).isGreaterThanOrEqualTo(1);
        assertThat(alertStatus(alertId)).isEqualTo("SENT");
        assertThat(alertAttempts(alertId)).isEqualTo(1);
        assertThat(alertLastError(alertId)).isNull();
    }

    @Test
    void senderNeverDryRunsEnabledWebhookWhenUrlIsBlank() {
        aiGatewayProperties.getExternalAlert().setWebhookEnabled(true);
        aiGatewayProperties.getExternalAlert().setWebhookUrl("   ");
        long alertId = insertPendingAlert("EXTERNAL_ALERT");

        int sent = senderService.sendPendingAlerts(10);

        assertThat(sent).isZero();
        assertThat(alertStatus(alertId)).isEqualTo("PENDING");
        assertThat(alertAttempts(alertId)).isEqualTo(1);
        assertThat(alertLastError(alertId)).contains("webhook URL is required");
        assertThat(webhookStub.requests()).isEmpty();
    }

    @Test
    void senderMarksUnsupportedPendingAlertFailedAndRecordsError() {
        long alertId = insertPendingAlert("UNSUPPORTED_CHANNEL");

        int sent = senderService.sendPendingAlerts(10);

        assertThat(sent).isGreaterThanOrEqualTo(0);
        assertThat(alertStatus(alertId)).isEqualTo("FAILED");
        assertThat(alertAttempts(alertId)).isEqualTo(1);
        assertThat(alertLastError(alertId)).contains("unsupported external alert channel");
    }

    @Test
    void senderPostsPendingExternalAlertToConfiguredWebhookWhenEnabled() {
        aiGatewayProperties.getExternalAlert().setWebhookEnabled(true);
        aiGatewayProperties.getExternalAlert().setWebhookUrl(webhookStub.url());
        webhookStub.enqueueStatus(200);
        long alertId = insertPendingAlert("EXTERNAL_ALERT");

        int sent = senderService.sendPendingAlerts(10);

        assertThat(sent).isGreaterThanOrEqualTo(1);
        assertThat(alertStatus(alertId)).isEqualTo("SENT");
        assertThat(alertAttempts(alertId)).isEqualTo(1);
        assertThat(alertLastError(alertId)).isNull();
        assertThat(webhookStub.requests())
                .anySatisfy(request -> {
                    assertThat(request.path()).isEqualTo("/ai-alerts");
                    assertThat(request.contentType()).contains("application/json");
                    assertThat(request.signature()).isNull();
                    assertThat(request.timestamp()).isNull();
                    assertThat(request.nonce()).isNull();
                    assertThat(request.body()).contains("AI_BUDGET_EXCEEDED");
                    assertThat(request.body()).contains("预算告警测试");
                });
    }

    @Test
    void senderSignsWebhookRequestWhenSigningIsEnabled() {
        aiGatewayProperties.getExternalAlert().setWebhookEnabled(true);
        aiGatewayProperties.getExternalAlert().setWebhookUrl(webhookStub.url());
        aiGatewayProperties.getExternalAlert().setWebhookSigningEnabled(true);
        aiGatewayProperties.getExternalAlert().setWebhookSigningSecret("local-test-signing-secret");
        webhookStub.enqueueStatus(200);
        long alertId = insertPendingAlert("EXTERNAL_ALERT");

        int sent = senderService.sendPendingAlerts(10);

        assertThat(sent).isGreaterThanOrEqualTo(1);
        assertThat(alertStatus(alertId)).isEqualTo("SENT");
        CapturedWebhookRequest request = webhookStub.requests().get(0);
        assertThat(request.timestamp()).isNotBlank();
        assertThat(request.nonce()).isNotBlank();
        assertThat(request.signature()).isEqualTo(
                "sha256=" + hmacSha256Hex(
                        "local-test-signing-secret",
                        request.timestamp() + "." + request.nonce() + "." + request.body()));
    }

    @Test
    void senderKeepsWebhookFailurePendingBeforeMaxAttempts() {
        aiGatewayProperties.getExternalAlert().setWebhookEnabled(true);
        aiGatewayProperties.getExternalAlert().setWebhookUrl(webhookStub.url());
        aiGatewayProperties.getExternalAlert().setMaxAttempts(3);
        webhookStub.enqueueStatus(500);
        long alertId = insertPendingAlert("EXTERNAL_ALERT");

        int sent = senderService.sendPendingAlerts(10);

        assertThat(sent).isGreaterThanOrEqualTo(0);
        assertThat(alertStatus(alertId)).isEqualTo("PENDING");
        assertThat(alertAttempts(alertId)).isEqualTo(1);
        assertThat(alertLastError(alertId)).contains("external alert webhook failed");
    }

    @Test
    void senderMovesWebhookFailureToDeadLetterAtMaxAttempts() {
        aiGatewayProperties.getExternalAlert().setWebhookEnabled(true);
        aiGatewayProperties.getExternalAlert().setWebhookUrl(webhookStub.url());
        aiGatewayProperties.getExternalAlert().setMaxAttempts(3);
        webhookStub.enqueueStatus(500);
        long alertId = insertPendingAlert("EXTERNAL_ALERT", 2);

        int sent = senderService.sendPendingAlerts(10);

        assertThat(sent).isGreaterThanOrEqualTo(0);
        assertThat(alertStatus(alertId)).isEqualTo("DEAD_LETTER");
        assertThat(alertAttempts(alertId)).isEqualTo(3);
        assertThat(alertLastError(alertId)).contains("external alert webhook failed");
    }

    @Test
    void senderClaimsPendingAlertBeforeWebhookCallToAvoidDuplicateExternalSend() throws Exception {
        aiGatewayProperties.getExternalAlert().setWebhookEnabled(true);
        aiGatewayProperties.getExternalAlert().setWebhookUrl(webhookStub.url());
        webhookStub.blockNextRequestUntilReleased();
        long alertId = insertPendingAlert("EXTERNAL_ALERT");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Integer> firstSend = executor.submit(() -> senderService.sendPendingAlerts(10));

        assertThat(webhookStub.awaitRequestCount(1, 2, TimeUnit.SECONDS)).isTrue();
        Future<Integer> secondSend = executor.submit(() -> senderService.sendPendingAlerts(10));

        try {
            assertThat(webhookStub.awaitRequestCount(2, 500, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            webhookStub.releaseBlockedRequests();
            executor.shutdownNow();
        }
        assertThat(firstSend.get(2, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(secondSend.get(2, TimeUnit.SECONDS)).isZero();
        assertThat(alertStatus(alertId)).isEqualTo("SENT");
        assertThat(alertAttempts(alertId)).isEqualTo(1);
        assertThat(webhookStub.requests()).hasSize(1);
    }

    @Test
    void schedulerDoesNothingWhenExternalAlertSchedulingIsDisabled() {
        long alertId = insertPendingAlert("EXTERNAL_ALERT");

        int sent = scheduler.dispatchPendingAlerts();

        assertThat(sent).isZero();
        assertThat(alertStatus(alertId)).isEqualTo("PENDING");
        assertThat(alertAttempts(alertId)).isZero();
        assertThat(alertLastError(alertId)).isNull();
    }

    @Test
    void schedulerDispatchesPendingAlertsWhenExternalAlertSchedulingIsEnabled() {
        aiGatewayProperties.getExternalAlert().setSchedulerEnabled(true);
        aiGatewayProperties.getExternalAlert().setSchedulerBatchSize(10);
        long alertId = insertPendingAlert("EXTERNAL_ALERT");

        int sent = scheduler.dispatchPendingAlerts();

        assertThat(sent).isGreaterThanOrEqualTo(1);
        assertThat(alertStatus(alertId)).isEqualTo("SENT");
        assertThat(alertAttempts(alertId)).isEqualTo(1);
        assertThat(alertLastError(alertId)).isNull();
    }

    private long insertPendingAlert(String channel) {
        return insertPendingAlert(channel, 0);
    }

    private long insertPendingAlert(String channel, int attempts) {
        jdbcClient.sql("""
                        INSERT INTO ai_external_alert_outbox
                            (order_id, alert_type, channel, payload, send_status, attempts, created_at)
                        VALUES
                            (:orderId, 'AI_BUDGET_EXCEEDED', :channel,
                             CAST(:payload AS JSON), 'PENDING', :attempts, '1970-01-01 00:00:00.000')
                        """)
                .param("orderId", orderId)
                .param("channel", channel)
                .param("payload", "{\"event\":\"AI_BUDGET_EXCEEDED\",\"message\":\"预算告警测试\"}")
                .param("attempts", attempts)
                .update();
        return jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    private void closeCurrentSenderTestAlerts() {
        if (orderId <= 0) {
            return;
        }
        jdbcClient.sql("""
                        UPDATE ai_external_alert_outbox
                        SET send_status = 'SENT',
                            last_error = NULL
                        WHERE order_id = :orderId
                          AND send_status IN ('PENDING', 'SENDING')
                        """)
                .param("orderId", orderId)
                .update();
    }

    private void closeStaleSenderTestAlerts() {
        jdbcClient.sql("""
                        UPDATE ai_external_alert_outbox alerts
                        JOIN orders o ON o.order_id = alerts.order_id
                        JOIN clinic c ON c.clinic_id = o.clinic_id
                        SET alerts.send_status = 'SENT',
                            alerts.last_error = NULL
                        WHERE (c.clinic_name LIKE '外部告警发送器测试诊所-%'
                               OR c.clinic_name LIKE 'AI测试诊所-%'
                               OR c.clinic_name LIKE 'DeepSeek测试诊所-%')
                          AND alerts.send_status IN ('PENDING', 'SENDING')
                        """)
                .update();
    }

    private String alertStatus(long alertId) {
        return jdbcClient.sql("""
                        SELECT send_status
                        FROM ai_external_alert_outbox
                        WHERE alert_id = :alertId
                        """)
                .param("alertId", alertId)
                .query(String.class)
                .single();
    }

    private int alertAttempts(long alertId) {
        return jdbcClient.sql("""
                        SELECT attempts
                        FROM ai_external_alert_outbox
                        WHERE alert_id = :alertId
                        """)
                .param("alertId", alertId)
                .query(Integer.class)
                .single();
    }

    private String alertLastError(long alertId) {
        return jdbcClient.sql("""
                        SELECT last_error
                        FROM ai_external_alert_outbox
                        WHERE alert_id = :alertId
                        """)
                .param("alertId", alertId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class ExternalAlertWebhookStub {
        private final HttpServer server;
        private final ExecutorService serverExecutor = Executors.newCachedThreadPool();
        private final List<CapturedWebhookRequest> requests = new ArrayList<>();
        private final List<Integer> statuses = new ArrayList<>();
        private boolean blockNextRequest;
        private CountDownLatch releaseBlockedRequest = new CountDownLatch(0);

        private ExternalAlertWebhookStub() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/ai-alerts", this::handleAlert);
                server.setExecutor(serverExecutor);
                server.start();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/ai-alerts";
        }

        private void enqueueStatus(int status) {
            synchronized (this) {
                statuses.add(status);
            }
        }

        private synchronized void reset() {
            requests.clear();
            statuses.clear();
            blockNextRequest = false;
            releaseBlockedRequest.countDown();
            releaseBlockedRequest = new CountDownLatch(0);
        }

        private synchronized List<CapturedWebhookRequest> requests() {
            return List.copyOf(requests);
        }

        private synchronized void blockNextRequestUntilReleased() {
            blockNextRequest = true;
            releaseBlockedRequest = new CountDownLatch(1);
        }

        private void releaseBlockedRequests() {
            releaseBlockedRequest.countDown();
        }

        private synchronized boolean awaitRequestCount(int expectedCount, long timeout, TimeUnit unit)
                throws InterruptedException {
            long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
            while (requests.size() < expectedCount) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
            }
            return true;
        }

        private void stop() {
            server.stop(0);
            serverExecutor.shutdownNow();
        }

        private void handleAlert(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            CountDownLatch releaseLatch = null;
            int status;
            synchronized (this) {
                requests.add(new CapturedWebhookRequest(
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        exchange.getRequestHeaders().getFirst("X-AI-Alert-Timestamp"),
                        exchange.getRequestHeaders().getFirst("X-AI-Alert-Nonce"),
                        exchange.getRequestHeaders().getFirst("X-AI-Alert-Signature"),
                        body));
                if (blockNextRequest) {
                    releaseLatch = releaseBlockedRequest;
                    blockNextRequest = false;
                }
                status = statuses.isEmpty() ? 200 : statuses.remove(0);
                notifyAll();
            }
            if (releaseLatch != null) {
                try {
                    releaseLatch.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }
    }

    private record CapturedWebhookRequest(
            String path,
            String contentType,
            String timestamp,
            String nonce,
            String signature,
            String body) {
    }
}
