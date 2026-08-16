package com.yuri.aiorder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "app.ai.provider=deepseek",
        "app.ai.deepseek.enabled=true",
        "app.ai.deepseek.api-key=test-deepseek-key",
        "app.ai.deepseek.model=deepseek-chat",
        "app.ai.input-token-cost-microusd=2",
        "app.ai.output-token-cost-microusd=8"
})
@AutoConfigureMockMvc
class AiGatewayDeepSeekTests {

    private static final long DOCTOR_USER_ID = 9911L;
    private static final long CS_USER_ID = 9912L;
    private static final long WORKER_USER_ID = 9913L;
    private static DeepSeekStubServer deepSeekServer;

    @DynamicPropertySource
    static void registerDeepSeekProperties(DynamicPropertyRegistry registry) {
        registry.add("app.ai.deepseek.base-url", () -> deepSeekServer.baseUrl());
    }

    @BeforeAll
    static void startDeepSeekStub() {
        deepSeekServer = new DeepSeekStubServer();
    }

    @AfterAll
    static void stopDeepSeekStub() {
        deepSeekServer.stop();
    }

    @AfterEach
    void closePendingExternalAlertsForCurrentOrder() {
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

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private AiGatewayProperties aiGatewayProperties;

    @Autowired
    private MockMvc mockMvc;

    private long clinicId;
    private long orderId;
    private String productType;

    @BeforeEach
    void setUp() {
        deepSeekServer.reset();
        aiGatewayProperties.setMaxRequestsPerUserHour(120);
        aiGatewayProperties.setProvider("deepseek");
        aiGatewayProperties.setDailyBudgetMicrousd(0);
        aiGatewayProperties.setBudgetNotificationEnabled(true);
        aiGatewayProperties.setBudgetCircuitBreakerEnabled(false);
        aiGatewayProperties.setAdminDailyBudgetMicrousd(0);
        aiGatewayProperties.setCsDailyBudgetMicrousd(0);
        aiGatewayProperties.setDoctorDailyBudgetMicrousd(0);
        aiGatewayProperties.setWorkerDailyBudgetMicrousd(0);
        aiGatewayProperties.getDeepseek().setDailyBudgetMicrousd(0);
        aiGatewayProperties.getDeepseek().setEnabled(true);
        aiGatewayProperties.getDeepseek().setApiKey("test-deepseek-key");
        aiGatewayProperties.getDeepseek().setModel("deepseek-chat");
        aiGatewayProperties.getLangchain().setEnabled(false);
        aiGatewayProperties.getLangchain().setProvider("deepseek");
        jdbcClient.sql("""
                        DELETE FROM ai_audit_log
                        WHERE actor_user_id IN (:doctorUserId, :csUserId, :workerUserId)
                        """)
                .param("doctorUserId", DOCTOR_USER_ID)
                .param("csUserId", CS_USER_ID)
                .param("workerUserId", WORKER_USER_ID)
                .update();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        productType = "AI_DEEPSEEK_" + suffix.substring(0, 10);
        jdbcClient.sql("INSERT INTO clinic (clinic_name) VALUES (:clinicName)")
                .param("clinicName", "DeepSeek测试诊所-" + suffix)
                .update();
        clinicId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();

        jdbcClient.sql("""
                        INSERT INTO orders
                            (order_no, clinic_id, doctor_user_id, cs_user_id, product_type,
                             form_data, internal_status, external_status, production_note)
                        VALUES
                            (:orderNo, :clinicId, :doctorUserId, :csUserId, :productType,
                             JSON_OBJECT('patient_name', '王五', 'tooth_position', '21'),
                             'IN_PRODUCTION', 'PRODUCING', '内部工序备注：不要泄露')
                        """)
                .param("orderNo", "AIDS" + suffix.substring(0, 11))
                .param("clinicId", clinicId)
                .param("doctorUserId", DOCTOR_USER_ID)
                .param("csUserId", CS_USER_ID)
                .param("productType", productType)
                .update();
        orderId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();

        jdbcClient.sql("""
                        INSERT INTO order_external_projection
                            (order_id, external_status, public_message)
                        VALUES
                            (:orderId, 'PRODUCING', '公开进度：正在制作。')
                        """)
                .param("orderId", orderId)
                .update();
    }

    @Test
    void enabledDeepSeekProviderCallsOpenAiCompatibleEndpointAndAuditsRealModel() throws Exception {
        deepSeekServer.enqueue("DeepSeek翻译草稿：Shade A2。");
        deepSeekServer.enqueue("DeepSeek客服摘要：外部状态 PRODUCING。");
        deepSeekServer.enqueue("DeepSeek医生公开答复：订单正在制作。");
        deepSeekServer.enqueue("DeepSeek生产备注草稿：按公开信息整理。");

        mockMvc.perform(post("/ai/translate")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"source_text\":\"Shade A2.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.translated_text").value(containsString("DeepSeek翻译草稿")));

        mockMvc.perform(post("/ai/cs-query")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"question\":\"订单概况？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value(containsString("DeepSeek客服摘要")));

        mockMvc.perform(post("/ai/order-query")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"question\":\"我的订单状态？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value(containsString("DeepSeek医生公开答复")));

        mockMvc.perform(post("/ai/order-query")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"question\":\"谁在做？工时多少？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value(containsString("只能回答公开进度")));

        mockMvc.perform(post("/ai/production-note")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft_note").value(containsString("DeepSeek生产备注草稿")));

        assertThat(deepSeekServer.requests()).hasSize(4);
        assertThat(deepSeekServer.requests()).allSatisfy(request -> {
            assertThat(request.path()).isEqualTo("/chat/completions");
            assertThat(request.authorization()).isEqualTo("Bearer test-deepseek-key");
            assertThat(request.body()).contains("\"model\":\"deepseek-chat\"");
        });
        assertThat(deepSeekServer.requests().get(2).body()).contains("公开进度：正在制作。");
        assertThat(deepSeekServer.requests().get(2).body()).doesNotContain("内部工序备注");
        assertThat(auditCountByModel("deepseek-chat")).isEqualTo(4L);
        assertThat(auditCountByStatus("SAFE_REFUSAL")).isEqualTo(1L);
    }

    @Test
    void enabledLangChainDeepSeekProviderRoutesAllAiAgentsThroughLangChain() throws Exception {
        aiGatewayProperties.setProvider("langchain-deepseek");
        aiGatewayProperties.getLangchain().setEnabled(true);
        deepSeekServer.enqueue("LangChain翻译草稿：Shade A2。");
        deepSeekServer.enqueue("LangChain客服摘要：外部状态 PRODUCING。");
        deepSeekServer.enqueue("LangChain医生公开答复：订单正在制作。");
        deepSeekServer.enqueue("LangChain生产备注草稿：按公开信息整理。");

        mockMvc.perform(post("/ai/translate")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"source_text\":\"Shade A2.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.translated_text").value(containsString("LangChain翻译草稿")));

        mockMvc.perform(post("/ai/cs-query")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"question\":\"订单概况？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value(containsString("LangChain客服摘要")));

        mockMvc.perform(post("/ai/order-query")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"question\":\"我的订单状态？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value(containsString("LangChain医生公开答复")));

        mockMvc.perform(post("/ai/order-query")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"question\":\"谁在做？工时多少？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value(containsString("只能回答公开进度")));

        mockMvc.perform(post("/ai/production-note")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft_note").value(containsString("LangChain生产备注草稿")));

        assertThat(deepSeekServer.requests()).hasSize(4);
        assertThat(deepSeekServer.requests()).allSatisfy(request -> {
            assertThat(request.path()).isIn("/chat/completions", "/v1/chat/completions");
            assertThat(request.authorization()).isEqualTo("Bearer test-deepseek-key");
            assertThat(request.body()).contains("\"model\"").contains("\"deepseek-chat\"");
        });
        assertThat(deepSeekServer.requests().get(2).body()).contains("公开进度：正在制作。");
        assertThat(deepSeekServer.requests().get(2).body()).doesNotContain("内部工序备注");
        assertThat(auditCountByModel("langchain-deepseek-chat")).isEqualTo(4L);
        assertThat(auditCountByStatus("SAFE_REFUSAL")).isEqualTo(1L);
    }

    @Test
    void deepSeekProviderRateLimitsRealModelCallsPerUserAndAuditsRejection() throws Exception {
        aiGatewayProperties.setMaxRequestsPerUserHour(2);
        deepSeekServer.enqueue("DeepSeek翻译草稿一。");
        deepSeekServer.enqueue("DeepSeek翻译草稿二。");

        mockMvc.perform(post("/ai/translate")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"source_text\":\"Shade A1.\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/ai/translate")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"source_text\":\"Shade A2.\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/ai/translate")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"source_text\":\"Shade A3.\"}"))
                .andExpect(status().isTooManyRequests());

        assertThat(deepSeekServer.requests()).hasSize(2);
        assertThat(auditCountByStatus("AI_RATE_LIMITED")).isEqualTo(1L);
    }

    @Test
    void deepSeekProviderAuditsEstimatedCostMicrousdFromTokenUsage() throws Exception {
        deepSeekServer.enqueue("DeepSeek翻译草稿：Shade A2。");

        mockMvc.perform(post("/ai/translate")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"source_text\":\"Shade A2.\"}"))
                .andExpect(status().isOk());

        assertThat(auditCostColumnCount()).isEqualTo(1L);
        assertThat(estimatedCostMicrousd()).isEqualTo(84L);
    }

    @Test
    void deepSeekProviderAuditsPromptVersionForAiTranslate() throws Exception {
        deepSeekServer.enqueue("DeepSeek提示词版本测试翻译。");

        mockMvc.perform(post("/ai/translate")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"source_text\":\"Shade A2.\"}"))
                .andExpect(status().isOk());

        assertThat(auditPromptVersionColumnCount()).isEqualTo(1L);
        assertThat(latestPromptVersionByAgent("AI_TRANSLATE")).isEqualTo("AI_TRANSLATE_V1");
    }

    @Test
    void deepSeekProviderGuardsSensitiveModelOutputAndAuditsIt() throws Exception {
        long baselineGuards = auditCountByStatus("AI_OUTPUT_GUARDED");
        deepSeekServer.enqueue("泄露：DEEPSEEK_API_KEY=sk-test 内部工序备注：不要泄露");

        String responseBody = mockMvc.perform(post("/ai/translate")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"source_text\":\"Shade A2.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.translated_text").value(containsString("安全保护")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(deepSeekServer.requests()).hasSize(1);
        assertThat(responseBody).doesNotContain("DEEPSEEK_API_KEY");
        assertThat(responseBody).doesNotContain("内部工序备注");
        assertThat(auditCountByStatus("AI_OUTPUT_GUARDED")).isEqualTo(baselineGuards + 1);
        assertThat(auditCountByModel("ai-governance-output-guard")).isEqualTo(1L);
        assertThat(latestPromptVersionByStatus("AI_OUTPUT_GUARDED")).isEqualTo("AI_TRANSLATE_V1");
    }

    @Test
    void deepSeekProviderAuditsBudgetExceededWhenDailyBudgetIsReached() throws Exception {
        long baselineOrderBudgetAlerts = auditCountByStatus("AI_BUDGET_EXCEEDED");
        long baselineRecentBudgetAlerts = recentAuditCountByStatus("AI_BUDGET_EXCEEDED");
        aiGatewayProperties.setDailyBudgetMicrousd(recentSuccessCostMicrousd() + 50);
        deepSeekServer.enqueue("DeepSeek预算告警测试翻译。");

        mockMvc.perform(post("/ai/translate")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"source_text\":\"Shade A2.\"}"))
                .andExpect(status().isOk());

        assertThat(deepSeekServer.requests()).hasSize(1);
        assertThat(auditCountByStatus("AI_BUDGET_EXCEEDED")).isEqualTo(baselineOrderBudgetAlerts + 1);

        mockMvc.perform(get("/ai/governance/summary")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.budget_alert_count").value(baselineRecentBudgetAlerts + 1))
                .andExpect(jsonPath("$.data.latest_budget_alert_at").exists());
    }

    @Test
    void deepSeekProviderNotifiesInternalUsersWhenDailyBudgetIsReached() throws Exception {
        long baselineEvents = notificationEventCount("AI_BUDGET_EXCEEDED");
        long baselineAdminNotifications = userNotificationCount(8001L, "AI_BUDGET_EXCEEDED");
        long baselineCsNotifications = userNotificationCount(8002L, "AI_BUDGET_EXCEEDED");
        long baselineDoctorNotifications = userNotificationCount(9701L, "AI_BUDGET_EXCEEDED");
        long baselineWorkerNotifications = userNotificationCount(9601L, "AI_BUDGET_EXCEEDED");
        aiGatewayProperties.setDailyBudgetMicrousd(recentSuccessCostMicrousd() + 50);
        deepSeekServer.enqueue("DeepSeek预算通知测试翻译。");

        mockMvc.perform(post("/ai/translate")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"source_text\":\"Shade A2.\"}"))
                .andExpect(status().isOk());

        assertThat(notificationEventCount("AI_BUDGET_EXCEEDED")).isEqualTo(baselineEvents + 1);
        assertThat(userNotificationCount(8001L, "AI_BUDGET_EXCEEDED")).isEqualTo(baselineAdminNotifications + 1);
        assertThat(userNotificationCount(8002L, "AI_BUDGET_EXCEEDED")).isEqualTo(baselineCsNotifications + 1);
        assertThat(userNotificationCount(9701L, "AI_BUDGET_EXCEEDED")).isEqualTo(baselineDoctorNotifications);
        assertThat(userNotificationCount(9601L, "AI_BUDGET_EXCEEDED")).isEqualTo(baselineWorkerNotifications);

        mockMvc.perform(get("/notifications")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 8001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].event").value("AI_BUDGET_EXCEEDED"))
                .andExpect(jsonPath("$.data[0].order_id").value(orderId))
                .andExpect(jsonPath("$.data[0].message").value(containsString("AI 预算")));
    }

    @Test
    void deepSeekProviderSkipsBudgetNotificationWhenNotificationStrategyIsDisabled() throws Exception {
        long baselineBudgetAlerts = auditCountByStatus("AI_BUDGET_EXCEEDED");
        long baselineEvents = notificationEventCount("AI_BUDGET_EXCEEDED");
        long baselineAdminNotifications = userNotificationCount(8001L, "AI_BUDGET_EXCEEDED");
        long baselineCsNotifications = userNotificationCount(8002L, "AI_BUDGET_EXCEEDED");
        aiGatewayProperties.setBudgetNotificationEnabled(false);
        aiGatewayProperties.setDailyBudgetMicrousd(recentSuccessCostMicrousd() + 50);
        deepSeekServer.enqueue("DeepSeek预算通知策略测试翻译。");

        mockMvc.perform(post("/ai/translate")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"source_text\":\"Shade A2.\"}"))
                .andExpect(status().isOk());

        assertThat(auditCountByStatus("AI_BUDGET_EXCEEDED")).isEqualTo(baselineBudgetAlerts + 1);
        assertThat(notificationEventCount("AI_BUDGET_EXCEEDED")).isEqualTo(baselineEvents);
        assertThat(userNotificationCount(8001L, "AI_BUDGET_EXCEEDED")).isEqualTo(baselineAdminNotifications);
        assertThat(userNotificationCount(8002L, "AI_BUDGET_EXCEEDED")).isEqualTo(baselineCsNotifications);
    }

    @Test
    void deepSeekProviderFallsBackWhenBudgetCircuitBreakerIsEnabledAndBudgetAlreadyExceeded() throws Exception {
        long baselineCircuitBreakers = auditCountByStatus("AI_BUDGET_CIRCUIT_OPEN");
        aiGatewayProperties.setBudgetCircuitBreakerEnabled(true);
        aiGatewayProperties.setDailyBudgetMicrousd(recentSuccessCostMicrousd() + 1);
        insertRecentSuccessCost(5);
        deepSeekServer.enqueue("DeepSeek should not be called when budget circuit is open.");

        mockMvc.perform(post("/ai/translate")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"source_text\":\"Shade A2.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.translated_text").value(containsString("翻译草稿")));

        assertThat(deepSeekServer.requests()).isEmpty();
        assertThat(auditCountByStatus("AI_BUDGET_CIRCUIT_OPEN")).isEqualTo(baselineCircuitBreakers + 1);
        assertThat(auditCountByModel("ai-governance-budget-circuit-open")).isEqualTo(1L);
    }

    @Test
    void deepSeekProviderCreatesExternalAlertOutboxWhenDailyBudgetIsReached() throws Exception {
        long baselineBudgetAlerts = auditCountByStatus("AI_BUDGET_EXCEEDED");
        long baselineExternalAlerts = externalAlertCount("AI_BUDGET_EXCEEDED");
        aiGatewayProperties.setDailyBudgetMicrousd(recentSuccessCostMicrousd() + 50);
        deepSeekServer.enqueue("DeepSeek预算外部告警测试翻译。");

        mockMvc.perform(post("/ai/translate")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"source_text\":\"Shade A2.\"}"))
                .andExpect(status().isOk());

        assertThat(deepSeekServer.requests()).hasSize(1);
        assertThat(auditCountByStatus("AI_BUDGET_EXCEEDED")).isEqualTo(baselineBudgetAlerts + 1);
        assertThat(externalAlertCount("AI_BUDGET_EXCEEDED")).isEqualTo(baselineExternalAlerts + 1);
        assertThat(latestExternalAlertStatus("AI_BUDGET_EXCEEDED")).isEqualTo("PENDING");
        assertThat(latestExternalAlertPayloadField("AI_BUDGET_EXCEEDED", "$.event"))
                .isEqualTo("AI_BUDGET_EXCEEDED");
        assertThat(latestExternalAlertPayloadField("AI_BUDGET_EXCEEDED", "$.orderNo"))
                .startsWith("AIDS");
        assertThat(latestExternalAlertPayloadText("AI_BUDGET_EXCEEDED"))
                .doesNotContain("Shade A2");
    }

    @Test
    void deepSeekProviderCreatesExternalAlertOutboxWhenBudgetCircuitBreakerOpens() throws Exception {
        long baselineCircuitBreakers = auditCountByStatus("AI_BUDGET_CIRCUIT_OPEN");
        long baselineExternalAlerts = externalAlertCount("AI_BUDGET_CIRCUIT_OPEN");
        aiGatewayProperties.setBudgetCircuitBreakerEnabled(true);
        aiGatewayProperties.setDailyBudgetMicrousd(recentSuccessCostMicrousd() + 1);
        insertRecentSuccessCost(5);
        deepSeekServer.enqueue("DeepSeek should not be called when budget circuit is open.");

        mockMvc.perform(post("/ai/translate")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"source_text\":\"Shade A2.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.translated_text").value(containsString("翻译草稿")));

        assertThat(deepSeekServer.requests()).isEmpty();
        assertThat(auditCountByStatus("AI_BUDGET_CIRCUIT_OPEN")).isEqualTo(baselineCircuitBreakers + 1);
        assertThat(externalAlertCount("AI_BUDGET_CIRCUIT_OPEN")).isEqualTo(baselineExternalAlerts + 1);
        assertThat(latestExternalAlertStatus("AI_BUDGET_CIRCUIT_OPEN")).isEqualTo("PENDING");
        assertThat(latestExternalAlertPayloadField("AI_BUDGET_CIRCUIT_OPEN", "$.event"))
                .isEqualTo("AI_BUDGET_CIRCUIT_OPEN");
        assertThat(latestExternalAlertPayloadText("AI_BUDGET_CIRCUIT_OPEN"))
                .doesNotContain("Shade A2");
    }

    @Test
    void deepSeekProviderFallsBackWhenCsRoleBudgetCircuitBreakerIsOpen() throws Exception {
        long baselineRoleCircuitBreakers = auditCountByStatus("AI_BUDGET_ROLE_CIRCUIT_OPEN");
        long baselineExternalAlerts = externalAlertCount("AI_BUDGET_ROLE_CIRCUIT_OPEN");
        aiGatewayProperties.setBudgetCircuitBreakerEnabled(true);
        aiGatewayProperties.setCsDailyBudgetMicrousd(recentSuccessCostMicrousdForRole("CS") + 1);
        insertRecentSuccessCostForRole("CS", 5);
        deepSeekServer.enqueue("DeepSeek should not be called when CS role budget circuit is open.");

        mockMvc.perform(post("/ai/translate")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"source_text\":\"Shade A2.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.translated_text").value(containsString("翻译草稿")));

        assertThat(deepSeekServer.requests()).isEmpty();
        assertThat(auditCountByStatus("AI_BUDGET_ROLE_CIRCUIT_OPEN"))
                .isEqualTo(baselineRoleCircuitBreakers + 1);
        assertThat(auditCountByModel("ai-governance-budget-role-circuit-open")).isEqualTo(1L);
        assertThat(externalAlertCount("AI_BUDGET_ROLE_CIRCUIT_OPEN")).isEqualTo(baselineExternalAlerts + 1);
        assertThat(latestExternalAlertStatus("AI_BUDGET_ROLE_CIRCUIT_OPEN")).isEqualTo("PENDING");
        assertThat(latestExternalAlertPayloadField("AI_BUDGET_ROLE_CIRCUIT_OPEN", "$.event"))
                .isEqualTo("AI_BUDGET_ROLE_CIRCUIT_OPEN");
        assertThat(latestExternalAlertPayloadField("AI_BUDGET_ROLE_CIRCUIT_OPEN", "$.role"))
                .isEqualTo("CS");
    }

    @Test
    void deepSeekProviderFallsBackWhenDeepSeekModelBudgetCircuitBreakerIsOpen() throws Exception {
        long baselineModelCircuitBreakers = auditCountByStatus("AI_BUDGET_MODEL_CIRCUIT_OPEN");
        long baselineExternalAlerts = externalAlertCount("AI_BUDGET_MODEL_CIRCUIT_OPEN");
        aiGatewayProperties.setBudgetCircuitBreakerEnabled(true);
        aiGatewayProperties.getDeepseek().setDailyBudgetMicrousd(recentSuccessCostMicrousdForModel("deepseek-chat") + 1);
        insertRecentSuccessCostForModel("deepseek-chat", 5);
        deepSeekServer.enqueue("DeepSeek should not be called when model budget circuit is open.");

        mockMvc.perform(post("/ai/translate")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"source_text\":\"Shade A2.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.translated_text").value(containsString("翻译草稿")));

        assertThat(deepSeekServer.requests()).isEmpty();
        assertThat(auditCountByStatus("AI_BUDGET_MODEL_CIRCUIT_OPEN"))
                .isEqualTo(baselineModelCircuitBreakers + 1);
        assertThat(auditCountByModel("ai-governance-budget-model-circuit-open")).isEqualTo(1L);
        assertThat(externalAlertCount("AI_BUDGET_MODEL_CIRCUIT_OPEN")).isEqualTo(baselineExternalAlerts + 1);
        assertThat(latestExternalAlertStatus("AI_BUDGET_MODEL_CIRCUIT_OPEN")).isEqualTo("PENDING");
        assertThat(latestExternalAlertPayloadField("AI_BUDGET_MODEL_CIRCUIT_OPEN", "$.event"))
                .isEqualTo("AI_BUDGET_MODEL_CIRCUIT_OPEN");
        assertThat(latestExternalAlertPayloadField("AI_BUDGET_MODEL_CIRCUIT_OPEN", "$.model"))
                .isEqualTo("deepseek-chat");
    }

    @Test
    void deepSeekProviderRetriesTransientServerFailureBeforeAuditingSuccess() throws Exception {
        deepSeekServer.enqueueFailure(500);
        deepSeekServer.enqueue("DeepSeek重试后翻译草稿：Shade A2。");

        mockMvc.perform(post("/ai/translate")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"source_text\":\"Shade A2.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.translated_text").value(containsString("DeepSeek重试后翻译草稿")));

        assertThat(deepSeekServer.requests()).hasSize(2);
        assertThat(auditCountByModel("deepseek-chat")).isEqualTo(1L);
    }

    @Test
    void deepSeekProviderAuditsModelFailureWhenRetriesAreExhausted() throws Exception {
        deepSeekServer.enqueueFailure(500);
        deepSeekServer.enqueueFailure(500);

        mockMvc.perform(post("/ai/translate")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"source_text\":\"Shade A2.\"}"))
                .andExpect(status().isServiceUnavailable());

        assertThat(deepSeekServer.requests()).hasSize(2);
        assertThat(auditCountByStatus("AI_MODEL_FAILED")).isEqualTo(1L);
    }

    private long auditCountByModel(String modelName) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM ai_audit_log
                        WHERE order_id = :orderId
                          AND model_name = :modelName
                        """)
                .param("orderId", orderId)
                .param("modelName", modelName)
                .query(Long.class)
                .single();
    }

    private long auditCountByStatus(String status) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM ai_audit_log
                        WHERE order_id = :orderId
                          AND result_status = :status
                        """)
                .param("orderId", orderId)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private long recentAuditCountByStatus(String status) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM ai_audit_log
                        WHERE result_status = :status
                          AND created_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 24 HOUR)
                        """)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private long recentSuccessCostMicrousd() {
        return jdbcClient.sql("""
                        SELECT COALESCE(SUM(estimated_cost_microusd), 0)
                        FROM ai_audit_log
                        WHERE result_status = 'SUCCESS'
                          AND created_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 24 HOUR)
                        """)
                .query(Long.class)
                .single();
    }

    private long recentSuccessCostMicrousdForRole(String actorRole) {
        return jdbcClient.sql("""
                        SELECT COALESCE(SUM(estimated_cost_microusd), 0)
                        FROM ai_audit_log
                        WHERE result_status = 'SUCCESS'
                          AND actor_role = :actorRole
                          AND created_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 24 HOUR)
                        """)
                .param("actorRole", actorRole)
                .query(Long.class)
                .single();
    }

    private long recentSuccessCostMicrousdForModel(String modelName) {
        return jdbcClient.sql("""
                        SELECT COALESCE(SUM(estimated_cost_microusd), 0)
                        FROM ai_audit_log
                        WHERE result_status = 'SUCCESS'
                          AND model_name = :modelName
                          AND created_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 24 HOUR)
                        """)
                .param("modelName", modelName)
                .query(Long.class)
                .single();
    }

    private long notificationEventCount(String eventType) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM notification_event
                        WHERE event_type = :eventType
                        """)
                .param("eventType", eventType)
                .query(Long.class)
                .single();
    }

    private long userNotificationCount(long userId, String eventType) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM user_notification un
                        JOIN notification_event ne ON ne.event_id = un.event_id
                        WHERE un.user_id = :userId
                          AND ne.event_type = :eventType
                        """)
                .param("userId", userId)
                .param("eventType", eventType)
                .query(Long.class)
                .single();
    }

    private long externalAlertCount(String alertType) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM ai_external_alert_outbox
                        WHERE order_id = :orderId
                          AND alert_type = :alertType
                        """)
                .param("orderId", orderId)
                .param("alertType", alertType)
                .query(Long.class)
                .single();
    }

    private String latestExternalAlertStatus(String alertType) {
        return jdbcClient.sql("""
                        SELECT send_status
                        FROM ai_external_alert_outbox
                        WHERE order_id = :orderId
                          AND alert_type = :alertType
                        ORDER BY alert_id DESC
                        LIMIT 1
                        """)
                .param("orderId", orderId)
                .param("alertType", alertType)
                .query(String.class)
                .single();
    }

    private String latestExternalAlertPayloadField(String alertType, String jsonPath) {
        return jdbcClient.sql("""
                        SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, :jsonPath))
                        FROM ai_external_alert_outbox
                        WHERE order_id = :orderId
                          AND alert_type = :alertType
                        ORDER BY alert_id DESC
                        LIMIT 1
                        """)
                .param("orderId", orderId)
                .param("alertType", alertType)
                .param("jsonPath", jsonPath)
                .query(String.class)
                .single();
    }

    private String latestExternalAlertPayloadText(String alertType) {
        return jdbcClient.sql("""
                        SELECT CAST(payload AS CHAR)
                        FROM ai_external_alert_outbox
                        WHERE order_id = :orderId
                          AND alert_type = :alertType
                        ORDER BY alert_id DESC
                        LIMIT 1
                        """)
                .param("orderId", orderId)
                .param("alertType", alertType)
                .query(String.class)
                .single();
    }

    private long auditCostColumnCount() {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM information_schema.columns
                        WHERE table_schema = DATABASE()
                          AND table_name = 'ai_audit_log'
                          AND column_name = 'estimated_cost_microusd'
                        """)
                .query(Long.class)
                .single();
    }

    private long auditPromptVersionColumnCount() {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM information_schema.columns
                        WHERE table_schema = DATABASE()
                          AND table_name = 'ai_audit_log'
                          AND column_name = 'prompt_version'
                        """)
                .query(Long.class)
                .single();
    }

    private long estimatedCostMicrousd() {
        return jdbcClient.sql("""
                        SELECT estimated_cost_microusd
                        FROM ai_audit_log
                        WHERE order_id = :orderId
                          AND result_status = 'SUCCESS'
                        ORDER BY ai_audit_id DESC
                        LIMIT 1
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    private String latestPromptVersionByAgent(String agentCode) {
        return jdbcClient.sql("""
                        SELECT prompt_version
                        FROM ai_audit_log
                        WHERE order_id = :orderId
                          AND agent_code = :agentCode
                        ORDER BY ai_audit_id DESC
                        LIMIT 1
                        """)
                .param("orderId", orderId)
                .param("agentCode", agentCode)
                .query(String.class)
                .single();
    }

    private String latestPromptVersionByStatus(String status) {
        return jdbcClient.sql("""
                        SELECT prompt_version
                        FROM ai_audit_log
                        WHERE order_id = :orderId
                          AND result_status = :status
                        ORDER BY ai_audit_id DESC
                        LIMIT 1
                        """)
                .param("orderId", orderId)
                .param("status", status)
                .query(String.class)
                .single();
    }

    private void insertRecentSuccessCost(long estimatedCostMicrousd) {
        jdbcClient.sql("""
                        INSERT INTO ai_audit_log
                            (order_id, actor_user_id, agent_code, request_context_type,
                             prompt_hash, model_name, input_token_count, output_token_count,
                             estimated_cost_microusd, result_status)
                        VALUES
                            (:orderId, :actorUserId, 'AI_TEST', 'BUDGET_CIRCUIT_TEST',
                             'hash-budget-circuit-open', 'deepseek-chat', 1, 1,
                             :estimatedCostMicrousd, 'SUCCESS')
                        """)
                .param("orderId", orderId)
                .param("actorUserId", CS_USER_ID)
                .param("estimatedCostMicrousd", estimatedCostMicrousd)
                .update();
    }

    private void insertRecentSuccessCostForRole(String actorRole, long estimatedCostMicrousd) {
        jdbcClient.sql("""
                        INSERT INTO ai_audit_log
                            (order_id, actor_user_id, actor_role, agent_code, request_context_type,
                             prompt_hash, model_name, input_token_count, output_token_count,
                             estimated_cost_microusd, result_status)
                        VALUES
                            (:orderId, :actorUserId, :actorRole, 'AI_TEST', 'ROLE_BUDGET_CIRCUIT_TEST',
                             'hash-role-budget-circuit-open', 'deepseek-chat', 1, 1,
                             :estimatedCostMicrousd, 'SUCCESS')
                        """)
                .param("orderId", orderId)
                .param("actorUserId", CS_USER_ID)
                .param("actorRole", actorRole)
                .param("estimatedCostMicrousd", estimatedCostMicrousd)
                .update();
    }

    private void insertRecentSuccessCostForModel(String modelName, long estimatedCostMicrousd) {
        jdbcClient.sql("""
                        INSERT INTO ai_audit_log
                            (order_id, actor_user_id, actor_role, agent_code, request_context_type,
                             prompt_hash, model_name, input_token_count, output_token_count,
                             estimated_cost_microusd, result_status)
                        VALUES
                            (:orderId, :actorUserId, 'CS', 'AI_TEST', 'MODEL_BUDGET_CIRCUIT_TEST',
                             'hash-model-budget-circuit-open', :modelName, 1, 1,
                             :estimatedCostMicrousd, 'SUCCESS')
                        """)
                .param("orderId", orderId)
                .param("actorUserId", CS_USER_ID)
                .param("modelName", modelName)
                .param("estimatedCostMicrousd", estimatedCostMicrousd)
                .update();
    }

    private static final class DeepSeekStubServer {
        private final HttpServer server;
        private final List<CapturedRequest> requests = new ArrayList<>();
        private final List<StubResponse> responses = new ArrayList<>();

        private DeepSeekStubServer() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/chat/completions", this::handleChatCompletions);
                server.createContext("/v1/chat/completions", this::handleChatCompletions);
                server.start();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void enqueue(String answer) {
            responses.add(new StubResponse(200, answer));
        }

        private void enqueueFailure(int statusCode) {
            responses.add(new StubResponse(statusCode, "DeepSeek temporary failure"));
        }

        private void reset() {
            requests.clear();
            responses.clear();
        }

        private List<CapturedRequest> requests() {
            return requests;
        }

        private void stop() {
            server.stop(0);
        }

        private void handleChatCompletions(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new CapturedRequest(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    body));
            StubResponse stubResponse = responses.isEmpty()
                    ? new StubResponse(200, "DeepSeek默认答复")
                    : responses.remove(0);
            if (stubResponse.statusCode() >= 400) {
                byte[] response = ("{\"error\":\"" + stubResponse.answer() + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(stubResponse.statusCode(), response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            byte[] response = ("""
                    {"choices":[{"message":{"content":%s}}],"usage":{"prompt_tokens":18,"completion_tokens":6}}
                    """.formatted(jsonString(stubResponse.answer()))).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        private String jsonString(String value) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    private record CapturedRequest(String path, String authorization, String body) {
    }

    private record StubResponse(int statusCode, String answer) {
    }
}
