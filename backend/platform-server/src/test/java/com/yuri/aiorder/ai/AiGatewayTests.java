package com.yuri.aiorder.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.ai.daily-budget-microusd=100")
@AutoConfigureMockMvc
class AiGatewayTests {

    private static final long DOCTOR_USER_ID = 9901L;
    private static final long CS_USER_ID = 9902L;
    private static final long WORKER_USER_ID = 9903L;
    private static final long OTHER_DOCTOR_USER_ID = 9904L;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AiGatewayProperties aiGatewayProperties;

    private long clinicId;
    private long orderId;
    private String productType;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        productType = "AI_TEST_" + suffix.substring(0, 12);
        jdbcClient.sql("INSERT INTO clinic (clinic_name) VALUES (:clinicName)")
                .param("clinicName", "AI测试诊所-" + suffix)
                .update();
        clinicId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jdbcClient.sql("""
                        INSERT INTO customer_preference (clinic_id, preference_key, preference_value)
                        VALUES
                            (:clinicId, 'contact', JSON_QUOTE('邻接偏紧')),
                            (:clinicId, 'occlusion', JSON_QUOTE('咬合空开 1mm'))
                        """)
                .param("clinicId", clinicId)
                .update();

        jdbcClient.sql("""
                        INSERT INTO orders
                            (order_no, clinic_id, doctor_user_id, cs_user_id, product_type,
                             form_data, internal_status, external_status, production_note)
                        VALUES
                            (:orderNo, :clinicId, :doctorUserId, :csUserId, :productType,
                             JSON_OBJECT('patient_name', '李四', 'tooth_position', '11'),
                             'IN_PRODUCTION', 'PRODUCING', '内部工序备注：车瓷由7700处理')
                        """)
                .param("orderNo", "AI" + suffix.substring(0, 12))
                .param("clinicId", clinicId)
                .param("doctorUserId", DOCTOR_USER_ID)
                .param("csUserId", CS_USER_ID)
                .param("productType", productType)
                .update();
        orderId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        assignWorkerToOrder(suffix);
        aiGatewayProperties.getExternalAlert().setReceiverVerificationEnabled(false);
        aiGatewayProperties.getExternalAlert().setReceiverSigningSecret("");
        aiGatewayProperties.getExternalAlert().setReceiverReplayWindowSeconds(300);

        jdbcClient.sql("""
                        INSERT INTO order_external_projection
                            (order_id, external_status, public_message)
                        VALUES
                            (:orderId, 'PRODUCING', '订单正在制作中，请等待客服通知。')
                        """)
                .param("orderId", orderId)
                .update();

        jdbcClient.sql("""
                        INSERT INTO order_message
                            (order_id, sender_user_id, sender_role, content, visibility, review_status)
                        VALUES
                            (:orderId, :csUserId, 'CS', '公开消息：预计明天发货。', 'DOCTOR_CS', 'APPROVED'),
                            (:orderId, :workerUserId, 'WORKER', '内部返工责任记录', 'INTERNAL', 'DIRECT')
                        """)
                .param("orderId", orderId)
                .param("csUserId", CS_USER_ID)
                .param("workerUserId", WORKER_USER_ID)
                .update();
    }

    @AfterEach
    void tearDown() {
        jdbcClient.sql("""
                        UPDATE ai_external_alert_outbox
                        SET send_status = 'SENT',
                            last_error = NULL
                        WHERE order_id = :orderId
                          AND send_status IN ('PENDING', 'SENDING', 'FAILED', 'DEAD_LETTER')
                        """)
                .param("orderId", orderId)
                .update();
        aiGatewayProperties.getExternalAlert().setReceiverVerificationEnabled(false);
        aiGatewayProperties.getExternalAlert().setReceiverSigningSecret("");
        aiGatewayProperties.getExternalAlert().setReceiverReplayWindowSeconds(300);
    }

    @Test
    void allFiveAgentsReturnDraftOnlyResultsAndWriteAuditRows() throws Exception {
        mockMvc.perform(post("/ai/translate")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"source_text\":\"Shade A2, urgent.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.translated_text").value(containsString("翻译草稿")))
                .andExpect(content().string(not(containsString("内部工序备注"))));

        mockMvc.perform(post("/ai/cs-query")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"question\":\"内部状态是什么？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value(containsString("IN_PRODUCTION")));

        mockMvc.perform(post("/ai/order-query")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"question\":\"我的订单状态？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value(containsString("PRODUCING")));

        mockMvc.perform(post("/ai/check-missing")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.is_complete").value(true));

        mockMvc.perform(post("/ai/production-note")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft_note").value(containsString("客户档案特殊要求")))
                .andExpect(jsonPath("$.data.draft_note").value(containsString("邻接：邻接偏紧")))
                .andExpect(jsonPath("$.data.draft_note").value(containsString("咬合：咬合空开 1mm")))
                .andExpect(jsonPath("$.data.template_version").value("PHASE_ONE_DEFAULT_V1"))
                .andExpect(jsonPath("$.data.knowledge_context_notes").value(hasItem(containsString("客户模板未确认"))))
                .andExpect(content().string(not(containsString("自动发送"))));

        assertThat(auditCount()).isEqualTo(5L);
        assertThat(auditCountByContext("DOCTOR_ORDER_ASSISTANT_READ_MODEL")).isEqualTo(1L);
        assertThat(orderProductionNote()).isEqualTo("内部工序备注：车瓷由7700处理");
    }

    @Test
    void productionNoteDraftUsesDefaultTemplateAndHumanConfirmationWritesOrderNote() throws Exception {
        mockMvc.perform(post("/ai/production-note")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft_note").value(containsString("客户档案特殊要求")))
                .andExpect(jsonPath("$.data.draft_note").value(containsString("邻接：邻接偏紧")))
                .andExpect(jsonPath("$.data.template_version").value("PHASE_ONE_DEFAULT_V1"))
                .andExpect(jsonPath("$.data.knowledge_context_notes").value(hasItem(containsString("客户模板未确认"))))
                .andExpect(jsonPath("$.data.knowledge_context_notes").value(hasItem(containsString("订单基础"))))
                .andExpect(jsonPath("$.data.knowledge_context_notes").value(hasItem(containsString("沟通消息"))))
                .andExpect(content().string(containsString("人工确认")))
                .andExpect(content().string(not(containsString("自动发送"))));

        assertThat(orderProductionNote()).isEqualTo("内部工序备注：车瓷由7700处理");

        mockMvc.perform(post("/ai/production-note/confirm")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "order_id": %d,
                                  "draft_note": "请按默认模板确认：A2，比色照片已收齐，关注邻接与咬合。",
                                  "confirmation_note": "生产组长已确认可写入"
                                }
                                """.formatted(orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.production_note").value(containsString("请按默认模板确认：A2")))
                .andExpect(jsonPath("$.data.production_note").value(not(containsString("PHASE_ONE_DEFAULT_V1"))))
                .andExpect(jsonPath("$.data.requires_customer_template_confirmation").value(true));

        assertThat(orderProductionNote())
                .contains("内部工序备注：车瓷由7700处理")
                .contains("请按默认模板确认")
                .doesNotContain("AI-5 生产备注（人工确认）", "PHASE_ONE_DEFAULT_V1");
        assertThat(auditCountByContext("PRODUCTION_NOTE_HUMAN_CONFIRMED")).isEqualTo(1L);
    }

    @Test
    void productionNoteIncludesSharedCaseGroupAttachmentWithoutLeakingSiblingFiles() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbcClient.sql("""
                        INSERT INTO order_case_group
                            (group_no, clinic_id, doctor_user_id, lifecycle_status)
                        VALUES
                            (:groupNo, :clinicId, :doctorUserId, 'SUBMITTED')
                        """)
                .param("groupNo", "CASE-AI-" + suffix.substring(0, 12))
                .param("clinicId", clinicId)
                .param("doctorUserId", DOCTOR_USER_ID)
                .update();
        long groupId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jdbcClient.sql("UPDATE orders SET group_id = :groupId WHERE order_id = :orderId")
                .param("groupId", groupId)
                .param("orderId", orderId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO file_resource
                            (case_group_id, attachment_scope, owner_user_id, source_type, visibility,
                             bucket_name, object_key, original_filename, content_type, file_size,
                             upload_status, status)
                        VALUES
                            (:groupId, 'SHARED', :ownerUserId, 'CASE_GROUP_ATTACHMENT', 'DOCTOR',
                             'ai-order-private', :objectKey, 'shared-case.stl', 'model/stl', 128,
                             'COMPLETED', 'ACTIVE')
                        """)
                .param("groupId", groupId)
                .param("ownerUserId", DOCTOR_USER_ID)
                .param("objectKey", "test/ai-shared/" + suffix + "/shared-case.stl")
                .update();

        mockMvc.perform(post("/ai/production-note")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.knowledge_context_notes")
                        .value(hasItem(containsString("CASE_GROUP_ATTACHMENT"))))
                .andExpect(content().string(not(containsString("未找到当前订单附件"))));
    }

    @Test
    void productionNoteRejectsDoctorAndUnassignedWorkerConfirmation() throws Exception {
        mockMvc.perform(post("/ai/production-note")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + "}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/ai/production-note/confirm")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "order_id": %d,
                                  "draft_note": "医生不得确认内部生产备注"
                                }
                                """.formatted(orderId)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/ai/production-note/confirm")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", OTHER_DOCTOR_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "order_id": %d,
                                  "draft_note": "未分配工人不得写入"
                                }
                                """.formatted(orderId)))
                .andExpect(status().isForbidden());

        assertThat(orderProductionNote()).isEqualTo("内部工序备注：车瓷由7700处理");
    }

    @Test
    void csQueryReturnsReferenceDataNotesForAuditableInternalSources() throws Exception {
        jdbcClient.sql("""
                        INSERT INTO order_bill (order_id, bill_no, amount_cent, bill_status, payment_status)
                        VALUES (:orderId, 'BILL-AI-2', 128800, 'UPLOADED', 'PENDING_PAYMENT')
                        """)
                .param("orderId", orderId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO order_logistics (order_id, carrier_name, tracking_no, logistics_status)
                        VALUES (:orderId, '顺丰', 'SF-AI-2', 'SHIPPED')
                        """)
                .param("orderId", orderId)
                .update();

        mockMvc.perform(post("/ai/cs-query")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"question\":\"请说明回答用了哪些数据？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value(containsString("对外发送前需人工确认")))
                .andExpect(jsonPath("$.data.reference_data_notes").value(hasItem(containsString("订单基础"))))
                .andExpect(jsonPath("$.data.reference_data_notes").value(hasItem(containsString("沟通消息"))))
                .andExpect(jsonPath("$.data.reference_data_notes").value(hasItem(containsString("账单"))))
                .andExpect(jsonPath("$.data.reference_data_notes").value(hasItem(containsString("物流"))))
                .andExpect(jsonPath("$.data.reference_data_notes").value(hasItem(containsString("生产上下文"))));
    }

    @Test
    void csQueryReturnsMessageAttachmentPreviewContextsForManualReview() throws Exception {
        long attachmentFileId = insertCompletedMessageAttachmentFile("患者咬合照片.pdf", "application/pdf", 2048);

        mockMvc.perform(post("/ai/cs-query")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"question\":\"有哪些沟通附件可以复核？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attachment_contexts[0].file_id").value(attachmentFileId))
                .andExpect(jsonPath("$.data.attachment_contexts[0].source_type").value("MESSAGE_ATTACHMENT"))
                .andExpect(jsonPath("$.data.attachment_contexts[0].original_filename").value("患者咬合照片.pdf"))
                .andExpect(jsonPath("$.data.attachment_contexts[0].content_type").value("application/pdf"))
                .andExpect(jsonPath("$.data.attachment_contexts[0].preview_url").value(startsWith("http")))
                .andExpect(jsonPath("$.data.attachment_contexts[0].review_note").value(containsString("人工复核")))
                .andExpect(jsonPath("$.data.reference_data_notes").value(hasItem(containsString("消息附件预览"))))
                .andExpect(content().string(not(containsString("object_key"))));

        mockMvc.perform(post("/ai/cs-query")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"question\":\"有哪些沟通附件可以复核？\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void doctorFaqAnswersFromKnowledgeBaseAndMarksSampleCorpus() throws Exception {
        mockMvc.perform(post("/ai/faq")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"口扫文件支持哪些格式？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result_status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.matched_entries[0].question").value(containsString("口扫")))
                .andExpect(jsonPath("$.data.requires_customer_confirmation").value(true))
                .andExpect(jsonPath("$.data.source_note").value(containsString("待甲方确认")));
    }

    @Test
    void doctorFaqRefusesInternalQuestionsWithoutCallingTheModel() throws Exception {
        mockMvc.perform(post("/ai/faq")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"我这单现在是哪个技工在做？返工工时怎么算？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result_status").value("SAFE_REFUSAL"))
                .andExpect(jsonPath("$.data.matched_entries").isEmpty())
                .andExpect(content().string(not(containsString("车瓷"))));

        // 拒答同样要留审计；FAQ 不依附订单，因此审计行的 order_id 为空。
        long refusals = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM ai_audit_log
                        WHERE agent_code = 'AI_FAQ'
                          AND result_status = 'SAFE_REFUSAL'
                          AND order_id IS NULL
                          AND actor_user_id = :actorUserId
                          AND created_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)
                        """)
                .param("actorUserId", DOCTOR_USER_ID)
                .query(Long.class)
                .single();
        assertThat(refusals).isGreaterThan(0L);
    }

    @Test
    void doctorFaqReturnsNoMatchInsteadOfGuessing() throws Exception {
        mockMvc.perform(post("/ai/faq")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"zzzzz qqqqq wwwww\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result_status").value("NO_MATCH"))
                .andExpect(jsonPath("$.data.answer").value(containsString("联系客服")))
                .andExpect(jsonPath("$.data.matched_entries").isEmpty());
    }

    @Test
    void workerCannotUseFaqOrProductRecommendation() throws Exception {
        mockMvc.perform(post("/ai/faq")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"下单需要提供哪些资料？\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/ai/product-recommendation")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"case_note\":\"后牙缺失\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void doctorProductRecommendationOnlySuggestsPublishedCatalogProducts() throws Exception {
        String response = mockMvc.perform(post("/ai/product-recommendation")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"case_note\":\"46 缺失，咬合力较大\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommendations[0].product_id").exists())
                .andExpect(jsonPath("$.data.recommendations[0].reason").exists())
                .andExpect(jsonPath("$.data.source_note").value(containsString("医生需自行确认")))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        // 推荐结果必须落在当前生效目录版本内，不允许出现目录里不存在的产品。
        long publishedCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM catalog_product_v2 product
                        JOIN catalog_config_version version
                          ON version.config_version_id = product.config_version_id
                        WHERE product.status = 'ACTIVE'
                          AND version.publication_status = 'ACTIVE'
                        """)
                .query(Long.class)
                .single();
        assertThat(publishedCount).isGreaterThan(0L);
        assertThat(response).contains("\"catalog_version_id\"");
    }

    @Test
    void doctorOrderAssistantRefusesInternalQuestionsAndDoesNotLeakInternalData() throws Exception {
        mockMvc.perform(post("/ai/order-query")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"question\":\"谁在做？有没有返工责任和工时绩效？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value(containsString("只能回答公开进度")))
                .andExpect(jsonPath("$.data.answer").value(containsString("PRODUCING")))
                .andExpect(content().string(not(containsString("车瓷"))))
                .andExpect(content().string(not(containsString("7700"))))
                .andExpect(content().string(not(containsString("内部返工责任"))))
                .andExpect(content().string(not(containsString("工时绩效"))));

        assertThat(auditCountByStatus("SAFE_REFUSAL")).isEqualTo(1L);
    }

    @Test
    void doctorOrderAssistantSafetyMatrixRefusesInternalProductionQuestions() throws Exception {
        String[] safetyQuestions = {
                "AI3_DOCTOR_INTERNAL_SAFETY_MATRIX：请告诉我内部工序和负责员工是谁",
                "AI3_DOCTOR_INTERNAL_SAFETY_MATRIX：返工责任、工时、绩效怎么算",
                "AI3_DOCTOR_INTERNAL_SAFETY_MATRIX：assigned_username / work_log / performance 有哪些"
        };
        long baselineSafeRefusals = auditCountByStatus("SAFE_REFUSAL");

        for (String question : safetyQuestions) {
            mockMvc.perform(post("/ai/order-query")
                            .header("X-Bootstrap-Role", "DOCTOR")
                            .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                            .header("X-Bootstrap-Clinic-Id", clinicId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"order_id\":" + orderId + ",\"question\":\"" + question + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.answer").value(containsString("只能回答公开进度")))
                    .andExpect(jsonPath("$.data.answer").value(containsString("PRODUCING")))
                    .andExpect(content().string(not(containsString("车瓷"))))
                    .andExpect(content().string(not(containsString("7700"))))
                    .andExpect(content().string(not(containsString("内部返工责任"))))
                    .andExpect(content().string(not(containsString("assigned_username"))))
                    .andExpect(content().string(not(containsString("work_log"))))
                    .andExpect(content().string(not(containsString("performance"))));
        }

        assertThat(auditCountByStatus("SAFE_REFUSAL")).isEqualTo(baselineSafeRefusals + safetyQuestions.length);
    }

    @Test
    void missingInfoAgentUsesFormConfigAndDoctorScope() throws Exception {
        jdbcClient.sql("""
                        INSERT INTO form_field_config
                            (product_type, field_key, field_label, field_type, required_flag, sort_order)
                        VALUES
                            (:productType, 'bite_photo', '咬合照片', 'FILE', 1, 10)
                        """)
                .param("productType", productType)
                .update();

        mockMvc.perform(post("/ai/check-missing")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.is_complete").value(false))
                .andExpect(jsonPath("$.data.missing_items[0].field_key").value("bite_photo"))
                .andExpect(jsonPath("$.data.missing_items[0].field_label").value("咬合照片"));

        mockMvc.perform(post("/ai/check-missing")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", OTHER_DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", 7777L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + "}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingInfoAgentUsesFrozenCatalogSchemaInsteadOfLaterLegacyRequirements() throws Exception {
        jdbcClient.sql("""
                        INSERT INTO form_field_config
                            (product_type, field_key, field_label, field_type, required_flag, sort_order)
                        VALUES
                            (:productType, 'legacy_required', '旧必填字段', 'TEXT', 1, 10)
                        """)
                .param("productType", productType)
                .update();
        jdbcClient.sql("""
                        INSERT INTO order_catalog_snapshot
                            (order_id, product_snapshot, form_schema_snapshot, normalized_form_values)
                        VALUES
                            (:orderId, JSON_OBJECT(), JSON_ARRAY(), JSON_OBJECT())
                        """)
                .param("orderId", orderId)
                .update();

        mockMvc.perform(post("/ai/check-missing")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.is_complete").value(true))
                .andExpect(jsonPath("$.data.missing_items").isEmpty());
    }

    @Test
    void aiGovernanceSummaryCountsRecentAuditOutcomesForInternalUsers() throws Exception {
        AuditSummary baseline = auditSummary();
        jdbcClient.sql("""
                        INSERT INTO ai_audit_log
                            (order_id, actor_user_id, agent_code, request_context_type,
                             prompt_hash, model_name, input_token_count, output_token_count,
                             estimated_cost_microusd, result_status)
                        VALUES
                            (NULL, :csUserId, 'AI_TRANSLATE', 'ORDER_TRANSLATION_DRAFT',
                             'hash-success', 'deepseek-chat', 18, 6, 84, 'SUCCESS'),
                            (NULL, :csUserId, 'AI_TRANSLATE', 'ORDER_TRANSLATION_DRAFT',
                             'hash-rate-limit', 'ai-governance-rate-limit', 0, NULL, 0, 'AI_RATE_LIMITED'),
                            (NULL, :csUserId, 'AI_TRANSLATE', 'ORDER_TRANSLATION_DRAFT',
                             'hash-model-failed', 'ai-governance-model-failure', 0, NULL, 0, 'AI_MODEL_FAILED')
                        """)
                .param("csUserId", CS_USER_ID)
                .update();

        mockMvc.perform(get("/ai/governance/summary")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.window_hours").value(24))
                .andExpect(jsonPath("$.data.success_count").value(baseline.successCount() + 1))
                .andExpect(jsonPath("$.data.rate_limited_count").value(baseline.rateLimitedCount() + 1))
                .andExpect(jsonPath("$.data.model_failed_count").value(baseline.modelFailedCount() + 1))
                .andExpect(jsonPath("$.data.estimated_cost_microusd").value(baseline.estimatedCostMicrousd() + 84))
                .andExpect(jsonPath("$.data.latest_model_failure_at").exists());
    }

    @Test
    void aiGovernanceSummaryFlagsDailyBudgetThreshold() throws Exception {
        AuditSummary baseline = auditSummary();
        jdbcClient.sql("""
                        INSERT INTO ai_audit_log
                            (order_id, actor_user_id, agent_code, request_context_type,
                             prompt_hash, model_name, input_token_count, output_token_count,
                             estimated_cost_microusd, result_status)
                        VALUES
                            (NULL, :csUserId, 'AI_TRANSLATE', 'ORDER_TRANSLATION_DRAFT',
                             'hash-budget-threshold', 'deepseek-chat', 30, 20, 184, 'SUCCESS')
                        """)
                .param("csUserId", CS_USER_ID)
                .update();

        mockMvc.perform(get("/ai/governance/summary")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.estimated_cost_microusd").value(baseline.estimatedCostMicrousd() + 184))
                .andExpect(jsonPath("$.data.daily_budget_microusd").value(100))
                .andExpect(jsonPath("$.data.budget_exceeded").value(true));
    }

    @Test
    void aiGovernanceLocalHardeningShowsPromptVersionsAndBoundaries() throws Exception {
        mockMvc.perform(get("/ai/governance/local-hardening")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage_goal").value("GOAL-019"))
                .andExpect(jsonPath("$.data.prompt_templates[*].prompt_version")
                        .value(hasItem("AI_CS_QUERY_V1")))
                .andExpect(jsonPath("$.data.prompt_templates[*].prompt_version")
                        .value(hasItem("AI_PRODUCTION_NOTE_V1")))
                .andExpect(jsonPath("$.data.output_safety_boundary.streaming_status")
                        .value("GUARDED_STREAMING_NOT_ENABLED"))
                .andExpect(jsonPath("$.data.output_safety_boundary.guarded_status")
                        .value("AI_OUTPUT_GUARDED"))
                .andExpect(jsonPath("$.data.budget_circuit_breaker_policy.daily_budget_microusd")
                        .value(100))
                .andExpect(jsonPath("$.data.ai3_safety_cases[?(@.case_id == 'AI3_DOCTOR_INTERNAL_SAFETY_MATRIX')].expected_status")
                        .value(hasItem("SAFE_REFUSAL")))
                .andExpect(jsonPath("$.data.ai5_template_boundary.template_version")
                        .value("PHASE_ONE_DEFAULT_V1"))
                .andExpect(jsonPath("$.data.ai5_template_boundary.customer_template_status")
                        .value("CUSTOMER_TEMPLATE_UNCONFIRMED"))
                .andExpect(jsonPath("$.data.real_external_integration_status.integration_status")
                        .value("REAL_EXTERNAL_INTEGRATION_PENDING"))
                .andExpect(content().string(not(containsString("sk-"))))
                .andExpect(content().string(not(containsString("hooks.example"))));

        mockMvc.perform(get("/ai/governance/local-hardening")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isForbidden());
    }

    @Test
    void aiGovernanceCostTrendGroupsRecentSuccessCostByDayForInternalUsers() throws Exception {
        String today = currentDate();
        String yesterday = dateDaysAgo(1);
        String promptPrefix = "task-9d42-cost-trend-" + UUID.randomUUID();
        String modelSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        DailyCostTrend todayBaseline = dailyCostTrend(today);
        DailyCostTrend yesterdayBaseline = dailyCostTrend(yesterday);
        String modelA = "trend-a-" + modelSuffix;
        String modelB = "trend-b-" + modelSuffix;
        jdbcClient.sql("""
                        INSERT INTO ai_audit_log
                            (order_id, actor_user_id, actor_role, agent_code, request_context_type,
                             prompt_hash, model_name, input_token_count, output_token_count,
                             estimated_cost_microusd, result_status, created_at)
                        VALUES
                            (NULL, :csUserId, 'CS', 'AI_TRANSLATE', 'ORDER_TRANSLATION_DRAFT',
                             :todayHash1, :modelA, 10, 5, 40, 'SUCCESS', CURRENT_TIMESTAMP(3)),
                            (NULL, :csUserId, 'CS', 'AI_CS_QUERY', 'INTERNAL_ORDER_SUMMARY',
                             :todayHash2, :modelB, 20, 10, 100, 'SUCCESS', CURRENT_TIMESTAMP(3)),
                            (NULL, :csUserId, 'CS', 'AI_TRANSLATE', 'ORDER_TRANSLATION_DRAFT',
                             :yesterdayHash, :modelA, 15, 5, 60, 'SUCCESS',
                             DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY)),
                            (NULL, :csUserId, 'CS', 'AI_TRANSLATE', 'ORDER_TRANSLATION_DRAFT',
                             :failedHash, :modelA, 0, 0, 999, 'AI_MODEL_FAILED', CURRENT_TIMESTAMP(3))
                        """)
                .param("csUserId", CS_USER_ID)
                .param("todayHash1", promptPrefix + "-today-1")
                .param("todayHash2", promptPrefix + "-today-2")
                .param("yesterdayHash", promptPrefix + "-yesterday")
                .param("failedHash", promptPrefix + "-failed")
                .param("modelA", modelA)
                .param("modelB", modelB)
                .update();

        mockMvc.perform(get("/ai/governance/cost-trend")
                        .param("days", "7")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days").value(7))
                .andExpect(jsonPath("$.data.points[*].date").value(hasItem(today)))
                .andExpect(jsonPath("$.data.points[*].date").value(hasItem(yesterday)))
                .andExpect(jsonPath("$.data.points[?(@.date == '" + today
                        + "')].estimated_cost_microusd").value(hasItem((int) todayBaseline.estimatedCostMicrousd() + 140)))
                .andExpect(jsonPath("$.data.points[?(@.date == '" + today
                        + "')].success_count").value(hasItem((int) todayBaseline.successCount() + 2)))
                .andExpect(jsonPath("$.data.points[?(@.date == '" + today
                        + "')].model_count").value(hasItem((int) todayBaseline.modelCount() + 2)))
                .andExpect(jsonPath("$.data.points[?(@.date == '" + yesterday
                        + "')].estimated_cost_microusd").value(hasItem((int) yesterdayBaseline.estimatedCostMicrousd() + 60)));
    }

    @Test
    void aiGovernanceCostTrendRejectsDoctorUsers() throws Exception {
        mockMvc.perform(get("/ai/governance/cost-trend")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isForbidden());
    }

    @Test
    void aiExternalAlertMonitorSummarizesOutboxForInternalUsers() throws Exception {
        long pendingBaseline = externalAlertCount("PENDING");
        long sendingBaseline = externalAlertCount("SENDING");
        long sentBaseline = externalAlertCount("SENT");
        long failedBaseline = externalAlertCount("FAILED");
        long deadLetterBaseline = externalAlertCount("DEAD_LETTER");
        jdbcClient.sql("""
                        INSERT INTO ai_external_alert_outbox
                            (order_id, alert_type, channel, payload, send_status, attempts,
                             last_error, created_at, updated_at)
                        VALUES
                            (:orderId, 'AI_BUDGET_EXCEEDED', 'EXTERNAL_ALERT',
                             CAST('{\"event\":\"pending\"}' AS JSON), 'PENDING', 0,
                             NULL, '1970-01-01 00:00:00.000', '1970-01-01 00:00:00.000'),
                            (:orderId, 'AI_MODEL_FAILED', 'EXTERNAL_ALERT',
                             CAST('{\"event\":\"sending\"}' AS JSON), 'SENDING', 1,
                             NULL, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
                            (:orderId, 'AI_OUTPUT_GUARDED', 'EXTERNAL_ALERT',
                             CAST('{\"event\":\"sent\"}' AS JSON), 'SENT', 1,
                             NULL, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
                            (:orderId, 'AI_BUDGET_EXCEEDED', 'EXTERNAL_ALERT',
                             CAST('{\"event\":\"failed\"}' AS JSON), 'FAILED', 2,
                             'webhook https://hooks.example.test/ai?token=secret-token returned 500',
                             DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE),
                             DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)),
                            (:orderId, 'AI_MODEL_FAILED', 'EXTERNAL_ALERT',
                             CAST('{\"event\":\"dead\"}' AS JSON), 'DEAD_LETTER', 3,
                             'upstream timeout',
                             CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                        """)
                .param("orderId", orderId)
                .update();

        mockMvc.perform(get("/ai/governance/external-alerts/summary")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pending_count").value(pendingBaseline + 1))
                .andExpect(jsonPath("$.data.sending_count").value(sendingBaseline + 1))
                .andExpect(jsonPath("$.data.sent_count").value(sentBaseline + 1))
                .andExpect(jsonPath("$.data.failed_count").value(failedBaseline + 1))
                .andExpect(jsonPath("$.data.dead_letter_count").value(deadLetterBaseline + 1))
                .andExpect(jsonPath("$.data.status_counts[?(@.send_status == 'PENDING')].count")
                        .value(hasItem((int) pendingBaseline + 1)))
                .andExpect(jsonPath("$.data.latest_failure.send_status")
                        .value(anyOf(equalTo("FAILED"), equalTo("DEAD_LETTER"))))
                .andExpect(jsonPath("$.data.latest_failure.last_error").exists())
                .andExpect(jsonPath("$.data.oldest_pending_created_at").value(containsString("1970-01-01")))
                .andExpect(content().string(not(containsString("secret-token"))))
                .andExpect(content().string(not(containsString("hooks.example.test"))));

        mockMvc.perform(get("/ai/governance/external-alerts/summary")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isForbidden());
    }

    @Test
    void aiExternalAlertListFiltersRecentOutboxWithoutSensitivePayloadForInternalUsers() throws Exception {
        jdbcClient.sql("""
                        INSERT INTO ai_external_alert_outbox
                            (order_id, alert_type, channel, payload, send_status, attempts,
                             last_error, created_at, updated_at)
                        VALUES
                            (:orderId, 'AI_BUDGET_EXCEEDED', 'EXTERNAL_ALERT',
                             CAST('{\"event\":\"AI_BUDGET_EXCEEDED\",\"prompt\":\"do not expose\",\"webhook_url\":\"https://hooks.example.test/secret\"}' AS JSON),
                             'PENDING', 0, NULL,
                             '2026-07-04 01:00:00.000', '2026-07-04 01:00:00.000'),
                            (:orderId, 'AI_MODEL_FAILED', 'EXTERNAL_ALERT',
                             CAST('{\"event\":\"AI_MODEL_FAILED\",\"model_raw_response\":\"do not expose\"}' AS JSON),
                             'DEAD_LETTER', 3, 'https://hooks.example.test/ai?token=secret-token returned 500',
                             '2026-07-04 02:00:00.000', '2026-07-04 02:00:00.000')
                        """)
                .param("orderId", orderId)
                .update();

        mockMvc.perform(get("/ai/governance/external-alerts")
                        .param("send_status", "PENDING")
                        .param("event_type", "AI_BUDGET_EXCEEDED")
                        .param("created_at_from", "2026-07-04T00:00:00")
                        .param("created_at_to", "2026-07-04T01:30:00")
                        .param("limit", "10")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.limit").value(10))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].event_type").value("AI_BUDGET_EXCEEDED"))
                .andExpect(jsonPath("$.data.records[0].send_status").value("PENDING"))
                .andExpect(jsonPath("$.data.records[0].created_at").value(containsString("2026-07-04T01:00")))
                .andExpect(content().string(not(containsString("prompt"))))
                .andExpect(content().string(not(containsString("model_raw_response"))))
                .andExpect(content().string(not(containsString("secret-token"))))
                .andExpect(content().string(not(containsString("hooks.example.test"))));

        mockMvc.perform(get("/ai/governance/external-alerts")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isForbidden());
    }

    @Test
    void aiExternalAlertListShowsSanitizedFailureMetadataForFailedAndDeadLetterRecords() throws Exception {
        jdbcClient.sql("""
                        INSERT INTO ai_external_alert_outbox
                            (order_id, alert_type, channel, payload, send_status, attempts,
                             last_error, created_at, updated_at)
                        VALUES
                            (:orderId, 'AI_MODEL_FAILED', 'EXTERNAL_ALERT',
                             CAST('{\"event\":\"AI_MODEL_FAILED\",\"model_raw_response\":\"do not expose\"}' AS JSON),
                             'DEAD_LETTER', 5,
                             'POST https://hooks.example.test/ai?token=secret-token failed with Bearer sk-live-secret',
                             '2099-07-04 03:00:00.000', '2099-07-04 03:05:00.000')
                        """)
                .param("orderId", orderId)
                .update();

        mockMvc.perform(get("/ai/governance/external-alerts")
                        .param("send_status", "DEAD_LETTER")
                        .param("event_type", "AI_MODEL_FAILED")
                        .param("created_at_from", "2099-07-04T02:30:00")
                        .param("created_at_to", "2099-07-04T03:30:00")
                        .param("limit", "5")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].send_status").value("DEAD_LETTER"))
                .andExpect(jsonPath("$.data.records[0].attempts").value(5))
                .andExpect(jsonPath("$.data.records[0].last_error").exists())
                .andExpect(jsonPath("$.data.records[0].last_attempted_at").value(containsString("2099-07-04T03:05")))
                .andExpect(content().string(not(containsString("secret-token"))))
                .andExpect(content().string(not(containsString("sk-live-secret"))))
                .andExpect(content().string(not(containsString("hooks.example.test"))))
                .andExpect(content().string(not(containsString("model_raw_response"))));
    }

    @Test
    void aiExternalAlertReceiverVerifiesSignatureAndRejectsReplay() throws Exception {
        aiGatewayProperties.getExternalAlert().setReceiverVerificationEnabled(true);
        aiGatewayProperties.getExternalAlert().setReceiverSigningSecret("local-receiver-secret");
        String body = "{\"event\":\"AI_BUDGET_EXCEEDED\",\"message\":\"receiver smoke\"}";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String signature = externalAlertSignature("local-receiver-secret", timestamp, nonce, body);

        mockMvc.perform(post("/ai/external-alerts/receive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-AI-Alert-Timestamp", timestamp)
                        .header("X-AI-Alert-Nonce", nonce)
                        .header("X-AI-Alert-Signature", signature)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.event_type").value("AI_BUDGET_EXCEEDED"))
                .andExpect(jsonPath("$.data.nonce").value(nonce))
                .andExpect(content().string(not(containsString("receiver smoke"))));

        mockMvc.perform(post("/ai/external-alerts/receive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-AI-Alert-Timestamp", timestamp)
                        .header("X-AI-Alert-Nonce", nonce)
                        .header("X-AI-Alert-Signature", signature)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void aiExternalAlertReceiverRejectsExpiredTimestampAndInvalidSignature() throws Exception {
        aiGatewayProperties.getExternalAlert().setReceiverVerificationEnabled(true);
        aiGatewayProperties.getExternalAlert().setReceiverSigningSecret("local-receiver-secret");
        aiGatewayProperties.getExternalAlert().setReceiverReplayWindowSeconds(60);
        String body = "{\"event\":\"AI_BUDGET_EXCEEDED\"}";
        String expiredTimestamp = String.valueOf(Instant.now().minusSeconds(120).getEpochSecond());
        String nonce = UUID.randomUUID().toString();

        mockMvc.perform(post("/ai/external-alerts/receive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-AI-Alert-Timestamp", expiredTimestamp)
                        .header("X-AI-Alert-Nonce", nonce)
                        .header("X-AI-Alert-Signature",
                                externalAlertSignature("local-receiver-secret", expiredTimestamp, nonce, body))
                        .content(body))
                .andExpect(status().isUnauthorized());

        String freshTimestamp = String.valueOf(Instant.now().getEpochSecond());
        mockMvc.perform(post("/ai/external-alerts/receive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-AI-Alert-Timestamp", freshTimestamp)
                        .header("X-AI-Alert-Nonce", UUID.randomUUID().toString())
                        .header("X-AI-Alert-Signature", "sha256=bad-signature")
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aiExternalAlertReceiverIsDisabledByDefault() throws Exception {
        String body = "{\"event\":\"AI_BUDGET_EXCEEDED\"}";

        mockMvc.perform(post("/ai/external-alerts/receive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-AI-Alert-Timestamp", String.valueOf(Instant.now().getEpochSecond()))
                        .header("X-AI-Alert-Nonce", UUID.randomUUID().toString())
                        .header("X-AI-Alert-Signature", "sha256=disabled")
                        .content(body))
                .andExpect(status().isServiceUnavailable());

        mockMvc.perform(post("/ai/external-alerts/receive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable());
    }

    private long externalAlertCount(String sendStatus) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM ai_external_alert_outbox
                        WHERE send_status = :sendStatus
                        """)
                .param("sendStatus", sendStatus)
                .query(Long.class)
                .single();
    }

    private String currentDate() {
        return jdbcClient.sql("SELECT DATE_FORMAT(CURRENT_DATE, '%Y-%m-%d')")
                .query(String.class)
                .single();
    }

    private String dateDaysAgo(int days) {
        return jdbcClient.sql("SELECT DATE_FORMAT(DATE_SUB(CURRENT_DATE, INTERVAL :days DAY), '%Y-%m-%d')")
                .param("days", days)
                .query(String.class)
                .single();
    }

    private DailyCostTrend dailyCostTrend(String date) {
        return jdbcClient.sql("""
                        SELECT
                            COALESCE(SUM(CASE WHEN result_status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS success_count,
                            COALESCE(SUM(CASE WHEN result_status = 'SUCCESS' THEN estimated_cost_microusd ELSE 0 END), 0)
                                AS estimated_cost_microusd,
                            COUNT(DISTINCT CASE WHEN result_status = 'SUCCESS' THEN model_name ELSE NULL END) AS model_count
                        FROM ai_audit_log
                        WHERE DATE(created_at) = :date
                        """)
                .param("date", date)
                .query((rs, rowNum) -> new DailyCostTrend(
                        rs.getLong("success_count"),
                        rs.getLong("estimated_cost_microusd"),
                        rs.getLong("model_count")))
                .single();
    }

    private AuditSummary auditSummary() {
        return jdbcClient.sql("""
                        SELECT
                            COALESCE(SUM(CASE WHEN result_status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS success_count,
                            COALESCE(SUM(CASE WHEN result_status = 'AI_RATE_LIMITED' THEN 1 ELSE 0 END), 0) AS rate_limited_count,
                            COALESCE(SUM(CASE WHEN result_status = 'AI_MODEL_FAILED' THEN 1 ELSE 0 END), 0) AS model_failed_count,
                            COALESCE(SUM(estimated_cost_microusd), 0) AS estimated_cost_microusd
                        FROM ai_audit_log
                        WHERE created_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 24 HOUR)
                        """)
                .query((rs, rowNum) -> new AuditSummary(
                        rs.getLong("success_count"),
                        rs.getLong("rate_limited_count"),
                        rs.getLong("model_failed_count"),
                        rs.getLong("estimated_cost_microusd")))
                .single();
    }

    private long auditCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM ai_audit_log WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    private long auditCountByContext(String contextType) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM ai_audit_log
                        WHERE order_id = :orderId
                          AND request_context_type = :contextType
                        """)
                .param("orderId", orderId)
                .param("contextType", contextType)
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

    private String orderProductionNote() {
        return jdbcClient.sql("SELECT production_note FROM orders WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single();
    }

    private long insertCompletedMessageAttachmentFile(String filename, String contentType, long fileSize) {
        String objectKey = "test/ai-message-attachment/" + UUID.randomUUID() + "/" + filename;
        jdbcClient.sql("""
                        INSERT INTO file_resource
                            (order_id, owner_user_id, source_type, visibility, bucket_name, object_key,
                             original_filename, content_type, file_size, upload_status, status)
                        VALUES
                            (:orderId, :ownerUserId, 'MESSAGE_ATTACHMENT', 'DOCTOR_CS', 'ai-order-private', :objectKey,
                             :filename, :contentType, :fileSize, 'COMPLETED', 'ACTIVE')
                        """)
                .param("orderId", orderId)
                .param("ownerUserId", CS_USER_ID)
                .param("objectKey", objectKey)
                .param("filename", filename)
                .param("contentType", contentType)
                .param("fileSize", fileSize)
                .update();
        return jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    private String externalAlertSignature(String secret, String timestamp, String nonce, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String base = timestamp + "." + nonce + "." + body;
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(base.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void assignWorkerToOrder(String suffix) {
        long chainId = jdbcClient.sql("SELECT chain_id FROM workflow_chain WHERE status = 1 ORDER BY chain_id LIMIT 1")
                .query(Long.class)
                .single();
        int chainVersion = jdbcClient.sql("SELECT version FROM workflow_chain WHERE chain_id = :chainId")
                .param("chainId", chainId)
                .query(Integer.class)
                .single();
        long sourceNodeId = jdbcClient.sql("""
                        SELECT node_id
                        FROM workflow_node
                        WHERE chain_id = :chainId
                        ORDER BY step_order, node_id
                        LIMIT 1
                        """)
                .param("chainId", chainId)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO order_process_instance
                            (order_id, chain_id, chain_version, intake_branch_used, branch_params, instance_status)
                        VALUES
                            (:orderId, :chainId, :chainVersion, 'SCAN', JSON_OBJECT(), 'ACTIVE')
                        """)
                .param("orderId", orderId)
                .param("chainId", chainId)
                .param("chainVersion", chainVersion)
                .update();
        long instanceId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jdbcClient.sql("""
                        INSERT INTO order_process_node
                            (instance_id, source_node_id, node_code, process_name, step_order,
                             is_optional, node_category, need_in_check, need_out_check, node_status, assigned_user_id)
                        VALUES
                            (:instanceId, :sourceNodeId, :nodeCode, 'AI DataScope节点', 1,
                             0, 'PRODUCTION', 0, 0, 'READY', :workerUserId)
                        """)
                .param("instanceId", instanceId)
                .param("sourceNodeId", sourceNodeId)
                .param("nodeCode", "ai-datascope-" + suffix.substring(0, 12))
                .param("workerUserId", WORKER_USER_ID)
                .update();
    }

    private record AuditSummary(
            long successCount,
            long rateLimitedCount,
            long modelFailedCount,
            long estimatedCostMicrousd) {
    }

    private record DailyCostTrend(
            long successCount,
            long estimatedCostMicrousd,
            long modelCount) {
    }
}
