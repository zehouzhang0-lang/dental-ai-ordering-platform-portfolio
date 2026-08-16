package com.yuri.aiorder.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.BearerTokenService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class MessageDesignBillNotificationTests {

    private static final long CS_USER_ID = 19801L;
    private static final long WORKER_USER_ID = 19802L;
    private static final long DOCTOR_USER_ID = 19803L;
    private static final long OTHER_DOCTOR_USER_ID = 19804L;
    private static final long INTERNAL_REVIEWER_USER_ID = 19805L;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BearerTokenService bearerTokenService;

    private long clinicId;
    private long orderId;
    private long fileId;
    private long billFileId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbcClient.sql("INSERT INTO clinic (clinic_name) VALUES (:clinicName)")
                .param("clinicName", "协同测试诊所-" + suffix)
                .update();
        clinicId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        ensureMentionUsers();

        jdbcClient.sql("""
                        INSERT INTO orders
                            (order_no, clinic_id, doctor_user_id, cs_user_id, product_type,
                             internal_status, external_status, production_note)
                        VALUES
                            (:orderNo, :clinicId, :doctorUserId, :csUserId, 'COLLAB_TEST',
                             'PENDING_PRODUCTION_REVIEW', 'PENDING_REVIEW', '内部协同备注')
                        """)
                .param("orderNo", "CO" + suffix.substring(0, 12))
                .param("clinicId", clinicId)
                .param("doctorUserId", DOCTOR_USER_ID)
                .param("csUserId", CS_USER_ID)
                .update();
        orderId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        assignWorkerToOrder(suffix);
        createClaimedDesignTask();

        jdbcClient.sql("""
                        INSERT INTO file_resource
                            (order_id, owner_user_id, source_type, visibility, bucket_name,
                             object_key, original_filename, content_type, file_size, upload_status)
                        VALUES
                            (:orderId, :ownerUserId, 'DESIGN_DRAFT', 'INTERNAL', 'ai-order-private',
                             :objectKey, 'draft.pdf', 'application/pdf', 1024, 'COMPLETED')
                        """)
                .param("orderId", orderId)
                .param("ownerUserId", WORKER_USER_ID)
                .param("objectKey", "test/collab/" + suffix + ".pdf")
                .update();
        fileId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jdbcClient.sql("""
                        INSERT INTO file_resource
                            (order_id, owner_user_id, source_type, visibility, bucket_name,
                             object_key, original_filename, content_type, file_size, upload_status)
                        VALUES
                            (:orderId, :ownerUserId, 'BILL', 'DOCTOR_CS', 'ai-order-private',
                             :objectKey, 'bill.pdf', 'application/pdf', 1024, 'COMPLETED')
                        """)
                .param("orderId", orderId)
                .param("ownerUserId", CS_USER_ID)
                .param("objectKey", "test/collab/" + suffix + "-bill.pdf")
                .update();
        billFileId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    @Test
    void directMentionPersistsAttentionItemAndOnlyMentionedUserCanResolveIt() throws Exception {
        MvcResult sendResult = mockMvc.perform(post("/orders/{orderId}/messages", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"请确认交期\",\"mention_user_ids\":[" + DOCTOR_USER_ID + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mention_user_ids[0]").value(DOCTOR_USER_ID))
                .andReturn();
        long messageId = extractId(sendResult, "msg_id");

        mockMvc.perform(get("/messages/attention-items")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].message_id").value(messageId))
                .andExpect(jsonPath("$.data[0].mention_user_id").value(DOCTOR_USER_ID));

        mockMvc.perform(post("/messages/attention-items/{messageId}/resolve", messageId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk());

        mockMvc.perform(get("/messages/attention-items")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void doctorCanOnlyMentionCsAndNeverReceivesWorkerMentionIds() throws Exception {
        mockMvc.perform(get("/orders/{orderId}/message-mentionable-users", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].user_id").value(CS_USER_ID));

        mockMvc.perform(post("/orders/{orderId}/messages", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"请生产确认\",\"mention_user_ids\":[" + WORKER_USER_ID + "]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/orders/{orderId}/messages", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"内部生产确认\",\"visible_to\":\"ALL\",\"mention_user_ids\":[" + WORKER_USER_ID + "]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/orders/{orderId}/messages", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].mention_user_ids").isEmpty());
    }

    @Test
    void doctorSentCsOnlyMessageRemainsVisibleInOwnHistory() throws Exception {
        mockMvc.perform(post("/orders/{orderId}/messages", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"医生已发送的历史留言\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visible_to").value("CS_ONLY"))
                .andExpect(jsonPath("$.data.review_status").value("DIRECT"));

        mockMvc.perform(get("/orders/{orderId}/messages", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("医生已发送的历史留言")));

        mockMvc.perform(get("/orders/{orderId}/messages", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("医生已发送的历史留言")));

        mockMvc.perform(get("/orders/{orderId}/messages", orderId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_USER_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("医生已发送的历史留言"))));
    }

    @Test
    void csMentionDefaultsKeepEveryMentionedRecipientAbleToSeeTheMessage() throws Exception {
        mockMvc.perform(post("/orders/{orderId}/messages", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"生产确认\",\"mention_user_ids\":[" + WORKER_USER_ID + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visible_to").value("CS_WORKER"));

        mockMvc.perform(post("/orders/{orderId}/messages", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"全员确认\",\"mention_user_ids\":[" + DOCTOR_USER_ID + "," + WORKER_USER_ID + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visible_to").value("ALL"));
    }

    @Test
    void workerMentionOfDoctorCreatesNoDoctorAttentionOrMentionNotificationBeforeApproval() throws Exception {
        MvcResult sendResult = mockMvc.perform(post("/orders/{orderId}/messages", orderId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"请医生确认修复方案\",\"mention_user_ids\":[" + DOCTOR_USER_ID + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.review_status").value("PENDING_REVIEW"))
                .andReturn();
        long messageId = extractId(sendResult, "msg_id");

        assertThat(notificationCount("MESSAGE_MENTIONED", "DOCTOR")).isZero();
        mockMvc.perform(get("/messages/attention-items")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(post("/messages/{msgId}/review", messageId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isOk());

        assertThat(notificationCount("MESSAGE_MENTIONED", "DOCTOR")).isEqualTo(1L);
        mockMvc.perform(get("/messages/attention-items")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].message_id").value(messageId));
    }

    @Test
    void userWhoWasNotMentionedCannotResolveAnotherUsersAttentionItem() throws Exception {
        MvcResult sendResult = mockMvc.perform(post("/orders/{orderId}/messages", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"仅医生处理\",\"mention_user_ids\":[" + DOCTOR_USER_ID + "]}"))
                .andExpect(status().isOk())
                .andReturn();
        long messageId = extractId(sendResult, "msg_id");

        mockMvc.perform(post("/messages/attention-items/{messageId}/resolve", messageId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_USER_ID))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/messages/attention-items")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].message_id").value(messageId));
    }

    @Test
    void workerMessageRequiresReviewBeforeDoctorCanSeeItAndNotificationsStayPublic() throws Exception {
        MvcResult sendResult = mockMvc.perform(post("/orders/{orderId}/messages", orderId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"请医生确认颜色。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.review_status").value("PENDING_REVIEW"))
                .andReturn();
        long messageId = extractId(sendResult, "msg_id");

        assertThat(notificationCount("MESSAGE_PENDING_REVIEW", "CS")).isEqualTo(1L);
        assertThat(notificationCount("MESSAGE_RECEIVED", "DOCTOR")).isZero();

        mockMvc.perform(get("/orders/{orderId}/messages", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(content().string(not(containsString("请医生确认颜色"))));

        mockMvc.perform(post("/messages/{msgId}/review", messageId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"EDIT_AND_APPROVE\",\"edited_content\":\"请医生确认最终颜色。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.review_status").value("APPROVED"));

        mockMvc.perform(get("/orders/{orderId}/messages", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("请医生确认最终颜色")))
                .andExpect(content().string(not(containsString("内部协同备注"))));

        String payload = latestNotificationPayload("MESSAGE_RECEIVED", "DOCTOR");
        assertThat(payload).contains("请医生确认最终颜色");
        assertThat(payload).doesNotContain("内部协同备注");
        assertThat(userNotificationCount(DOCTOR_USER_ID)).isEqualTo(1L);
    }

    @Test
    void claimedDesignWorkerCanSendButLosesAccessAfterDesignTaskTransfer() throws Exception {
        jdbcClient.sql("""
                        UPDATE order_process_node node
                        JOIN order_process_instance instance ON instance.instance_id = node.instance_id
                        SET node.assigned_user_id = NULL
                        WHERE instance.order_id = :orderId
                          AND node.node_status = 'READY'
                        """)
                .param("orderId", orderId)
                .update();
        long workerPermissionCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM system_role role
                        JOIN system_role_permission role_permission ON role_permission.role_id = role.role_id
                        JOIN system_permission permission ON permission.permission_id = role_permission.permission_id
                        WHERE role.role_code = 'WORKER'
                          AND permission.permission_code = 'message:operate-production'
                        """)
                .query(Long.class)
                .single();
        assertThat(workerPermissionCount).isEqualTo(1L);

        String token = bearerTokenService.issue(new BootstrapIdentity(
                UserRole.WORKER,
                WORKER_USER_ID,
                null,
                "collab-worker",
                Set.of("message:operate-production"),
                "SELF"));
        mockMvc.perform(post("/orders/{orderId}/messages", orderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"生产审核员请求医生补充资料\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.review_status").value("PENDING_REVIEW"));

        jdbcClient.sql("""
                        UPDATE design_task
                        SET assigned_user_id = 9601
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .update();
        jdbcClient.sql("""
                        UPDATE orders
                        SET internal_status = 'IN_PRODUCTION',
                            external_status = 'PRODUCING'
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .update();
        mockMvc.perform(post("/orders/{orderId}/messages", orderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"不应越权发送\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void pendingMessageReviewQueueExposesOrderContextAndRejectStaysHiddenFromDoctor() throws Exception {
        MvcResult sendResult = mockMvc.perform(post("/orders/{orderId}/messages", orderId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"生产端建议调整咬合高度。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.review_status").value("PENDING_REVIEW"))
                .andReturn();
        long messageId = extractId(sendResult, "msg_id");

        mockMvc.perform(get("/messages/pending-review")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"msg_id\":" + messageId)))
                .andExpect(content().string(containsString("\"order_id\":" + orderId)))
                .andExpect(content().string(containsString("\"order_no\"")))
                .andExpect(content().string(containsString("\"product_type\":\"COLLAB_TEST\"")))
                .andExpect(content().string(containsString("\"external_status\":\"PENDING_REVIEW\"")));

        mockMvc.perform(post("/messages/{msgId}/review", messageId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\",\"review_note\":\"请改为内部沟通\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.review_status").value("REJECTED"))
                .andExpect(jsonPath("$.data.order_no").isString());

        mockMvc.perform(get("/orders/{orderId}/messages", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("生产端建议调整咬合高度"))));

        assertThat(notificationCount("MESSAGE_REVIEW_REJECTED", "WORKER")).isEqualTo(1L);
        assertThat(notificationCount("MESSAGE_RECEIVED", "DOCTOR")).isZero();
    }

    @Test
    void designDraftSubmissionInternalReviewAndDoctorConfirmUseNotificationFactSource() throws Exception {
        MvcResult uploadResult = mockMvc.perform(post("/orders/{orderId}/design-drafts", orderId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"file_ids\":[" + fileId
                                + "],\"upload_note\":\"新版设计稿\",\"submission_key\":\"notification-v1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.submitted_at").doesNotExist())
                .andReturn();
        long draftId = extractId(uploadResult, "draft_id");

        mockMvc.perform(get("/orders/{orderId}/design-drafts", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(post("/orders/{orderId}/design-drafts/{draftId}/submit", orderId, draftId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.submitted_at").isString());
        assertThat(notificationCount("DESIGN_DRAFT_SUBMITTED", "WORKER")).isEqualTo(1L);

        mockMvc.perform(post("/orders/{orderId}/design-drafts/{draftId}/internal-review", orderId, draftId)
                        .header("Authorization", "Bearer " + internalReviewerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_DOCTOR"));

        mockMvc.perform(get("/orders/{orderId}/design-drafts", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].draft_id").value(draftId))
                .andExpect(jsonPath("$.data[0].status").value("PENDING_DOCTOR"));

        mockMvc.perform(post("/orders/{orderId}/design-drafts/{draftId}/doctor-confirm", orderId, draftId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DOCTOR_CONFIRMED"));

        assertThat(notificationCount("DESIGN_DRAFT_INTERNAL_APPROVED", "DOCTOR")).isEqualTo(1L);
        assertThat(notificationCount("DESIGN_DRAFT_DOCTOR_CONFIRMED", "INTERNAL")).isEqualTo(1L);
        assertThat(userNotificationCount(DOCTOR_USER_ID)).isEqualTo(1L);
        assertThat(userNotificationCount(WORKER_USER_ID)).isEqualTo(1L);
    }

    @Test
    void designDraftUploadKeepsMultipleFilesInOneIdempotentVersion() throws Exception {
        long secondFileId = createDesignDraftFile("supplement.stl", "application/sla", 2048);

        MvcResult firstUpload = mockMvc.perform(post("/orders/{orderId}/design-drafts", orderId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"file_ids\":[" + fileId + "," + secondFileId
                                + "],\"upload_note\":\"V1 多文件\",\"submission_key\":\"multi-v1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.file_id").value(fileId))
                .andExpect(jsonPath("$.data.file_ids[0]").value(fileId))
                .andExpect(jsonPath("$.data.file_ids[1]").value(secondFileId))
                .andExpect(jsonPath("$.data.file_count").value(2))
                .andReturn();
        long firstDraftId = extractId(firstUpload, "draft_id");

        mockMvc.perform(post("/orders/{orderId}/design-drafts", orderId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"file_ids\":[" + fileId + "," + secondFileId
                                + "],\"upload_note\":\"V1 多文件\",\"submission_key\":\"multi-v1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft_id").value(firstDraftId))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.file_count").value(2));

        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM design_draft
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single()).isEqualTo(1L);
    }

    @Test
    void billAndLogisticsAreVisibleToDoctorAndShipmentUpdatesExternalProjection() throws Exception {
        mockMvc.perform(post("/orders/{orderId}/bill", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"file_id\":" + fileId + "}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/orders/{orderId}/bill", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"file_id\":" + billFileId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bill_status").value("UPLOADED"));
        markPaymentNotRequired();

        mockMvc.perform(get("/orders/{orderId}/bill", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bill_status").value("UPLOADED"))
                .andExpect(content().string(not(containsString("内部协同备注"))));

        markFinalOutCheckPassed();

        mockMvc.perform(post("/orders/{orderId}/logistics", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"顺丰速运\",\"tracking_no\":\"SF123456789\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logistics_status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.tracking_no").value("SF123456789"));

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.external_status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.tracking_no").value("SF123456789"))
                .andExpect(content().string(not(containsString("internal_status"))));

        mockMvc.perform(get("/orders/{orderId}/bill", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", OTHER_DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", 7777L))
                .andExpect(status().isForbidden());

        assertThat(notificationCount("BILL_UPLOADED", "DOCTOR")).isEqualTo(1L);
        assertThat(notificationCount("ORDER_SHIPPED", "DOCTOR")).isEqualTo(1L);
        assertThat(userNotificationCount(DOCTOR_USER_ID)).isEqualTo(2L);
    }

    @Test
    void csCanMaintainExternalPaymentStatusAndDoctorCanOnlyReadIt() throws Exception {
        mockMvc.perform(post("/orders/{orderId}/bill", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"file_id\":" + billFileId + ",\"amount_cents\":128800,\"currency\":\"CNY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bill_status").value("UPLOADED"))
                .andExpect(jsonPath("$.data.payment_status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.amount_cents").value(128800))
                .andExpect(jsonPath("$.data.currency").value("CNY"));

        mockMvc.perform(post("/orders/{orderId}/bill/payment-status", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_status\":\"PARTIALLY_PAID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bill_status").value("UPLOADED"))
                .andExpect(jsonPath("$.data.payment_status").value("PARTIALLY_PAID"));

        mockMvc.perform(get("/orders/{orderId}/bill", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payment_status").value("PARTIALLY_PAID"))
                .andExpect(jsonPath("$.data.amount_cents").value(128800))
                .andExpect(content().string(not(containsString("内部协同备注"))));

        mockMvc.perform(post("/orders/{orderId}/bill/payment-status", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_status\":\"PAID\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/orders/{orderId}/bill/payment-status", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_status\":\"EXTERNAL_GATEWAY_PAID\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void csCanRecordManualPaymentLedgerAndDoctorCanOnlyReadOwnOrderLedger() throws Exception {
        mockMvc.perform(post("/orders/{orderId}/payments", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount_cents\":128800,\"currency\":\"CNY\",\"payment_method\":\"BANK_TRANSFER\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/orders/{orderId}/bill", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"file_id\":" + billFileId + ",\"amount_cents\":128800,\"currency\":\"CNY\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/orders/{orderId}/payments", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount_cents": 128800,
                                  "currency": "CNY",
                                  "payment_method": "BANK_TRANSFER",
                                  "received_at": "2026-07-05T10:15:30",
                                  "payment_note": "一期人工收款记录"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount_cents").value(128800))
                .andExpect(jsonPath("$.data.payment_method").value("BANK_TRANSFER"));
        mockMvc.perform(get("/orders/{orderId}/bill", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payment_status").value("PAID"));

        mockMvc.perform(get("/orders/{orderId}/payments", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].amount_cents").value(128800))
                .andExpect(jsonPath("$.data[0].currency").value("CNY"))
                .andExpect(content().string(containsString("一期人工收款记录")))
                .andExpect(content().string(not(containsString("内部协同备注"))));

        mockMvc.perform(post("/orders/{orderId}/payments", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount_cents\":100,\"payment_method\":\"CASH\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/orders/{orderId}/payments", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", OTHER_DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isForbidden());
    }

    @Test
    void shipmentRequiresFinalOutCheckPassBeforeUpdatingExternalProjection() throws Exception {
        mockMvc.perform(post("/orders/{orderId}/logistics", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"carrier\":\"顺丰速运\",\"tracking_no\":\"SF-BLOCKED\"}"))
                .andExpect(status().isConflict())
                .andExpect(status().reason(containsString("final out-check pass is required before shipment")));

        assertThat(notificationCount("ORDER_SHIPPED", "DOCTOR")).isZero();

        markFinalOutCheckPassed();

        mockMvc.perform(post("/orders/{orderId}/logistics", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"顺丰速运\",\"tracking_no\":\"SF-PAYMENT-BLOCKED\"}"))
                .andExpect(status().isConflict())
                .andExpect(status().reason(containsString("payment must be paid or marked not required before shipment")));

        markPaymentNotRequired();
        mockMvc.perform(post("/orders/{orderId}/logistics", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"顺丰速运\",\"tracking_no\":\"SF-READY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logistics_status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.tracking_no").value("SF-READY"));

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.external_status").value("SHIPPED"));
    }

    @Test
    void csCanTrackLogisticsExceptionsWithoutLeakingInternalFollowUpToDoctor() throws Exception {
        markPaymentNotRequired();
        markFinalOutCheckPassed();

        mockMvc.perform(post("/orders/{orderId}/logistics", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"顺丰速运\",\"tracking_no\":\"SF-EXCEPTION\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logistics_status").value("SHIPPED"));

        mockMvc.perform(post("/orders/{orderId}/logistics/exception", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "logistics_status": "EXCEPTION",
                                  "follow_up_note": "客户反馈物流延迟，已联系顺丰催派"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").value(orderId))
                .andExpect(jsonPath("$.data.logistics_status").value("EXCEPTION"))
                .andExpect(jsonPath("$.data.last_follow_up_note").value(containsString("已联系顺丰催派")));

        mockMvc.perform(get("/logistics/orders")
                        .param("logistics_status", "EXCEPTION")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].order_id").value(orderId))
                .andExpect(jsonPath("$.data[0].tracking_no").value("SF-EXCEPTION"))
                .andExpect(jsonPath("$.data[0].last_follow_up_note").value(containsString("已联系顺丰催派")));

        mockMvc.perform(get("/orders/{orderId}/logistics", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logistics_status").value("SHIPPED"))
                .andExpect(content().string(not(containsString("已联系顺丰催派"))));

        mockMvc.perform(post("/orders/{orderId}/logistics/exception", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"logistics_status\":\"RESOLVED\",\"follow_up_note\":\"医生尝试处理\"}"))
                .andExpect(status().isForbidden());
    }

    private long notificationCount(String eventType, String audienceRole) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM notification_event
                        WHERE order_id = :orderId
                          AND event_type = :eventType
                          AND audience_role = :audienceRole
                        """)
                .param("orderId", orderId)
                .param("eventType", eventType)
                .param("audienceRole", audienceRole)
                .query(Long.class)
                .single();
    }

    private void ensureMentionUsers() {
        insertMentionUser(CS_USER_ID, "collab-cs", "CS");
        insertMentionUser(WORKER_USER_ID, "collab-worker", "WORKER");
        insertMentionUser(DOCTOR_USER_ID, "collab-doctor", "DOCTOR");
        insertMentionUser(OTHER_DOCTOR_USER_ID, "collab-other-doctor", "DOCTOR");
        insertMentionUser(INTERNAL_REVIEWER_USER_ID, "collab-reviewer", "WORKER");
        jdbcClient.sql("""
                        INSERT IGNORE INTO system_user_role (user_id, role_id)
                        SELECT :userId, role_id
                        FROM system_role
                        WHERE role_code = 'PROD_TEAM_LEAD'
                        """)
                .param("userId", INTERNAL_REVIEWER_USER_ID)
                .update();
    }

    private String internalReviewerToken() {
        return bearerTokenService.issue(new BootstrapIdentity(
                UserRole.WORKER,
                INTERNAL_REVIEWER_USER_ID,
                null,
                "collab-reviewer",
                Set.of("design-draft:internal-review"),
                "ALL"));
    }

    private void insertMentionUser(long userId, String username, String userType) {
        jdbcClient.sql("""
                        INSERT IGNORE INTO system_user
                            (user_id, username, password_hash, display_name, user_type, status)
                        VALUES
                            (:userId, :username, 'test-password-hash', :displayName, :userType, 'ACTIVE')
                        """)
                .param("userId", userId)
                .param("username", username)
                .param("displayName", username)
                .param("userType", userType)
                .update();
        jdbcClient.sql("""
                        INSERT IGNORE INTO system_user_role (user_id, role_id)
                        SELECT :userId, role_id
                        FROM system_role
                        WHERE role_code = :roleCode
                        """)
                .param("userId", userId)
                .param("roleCode", userType)
                .update();
    }

    private String latestNotificationPayload(String eventType, String audienceRole) {
        return jdbcClient.sql("""
                        SELECT CAST(payload AS CHAR)
                        FROM notification_event
                        WHERE order_id = :orderId
                          AND event_type = :eventType
                          AND audience_role = :audienceRole
                        ORDER BY event_id DESC
                        LIMIT 1
                        """)
                .param("orderId", orderId)
                .param("eventType", eventType)
                .param("audienceRole", audienceRole)
                .query(String.class)
                .single();
    }

    private long userNotificationCount(long userId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM user_notification un
                        JOIN notification_event ne ON ne.event_id = un.event_id
                        WHERE ne.order_id = :orderId
                          AND un.user_id = :userId
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    @Test
    void designDraftRejectReasonsArePersistedAndRevisionHistoryKeepsVisibilityBoundary() throws Exception {
        long firstFileId = createDesignDraftFile("internal-reject.stl", "application/sla", 1024);
        MvcResult firstUpload = mockMvc.perform(post("/orders/{orderId}/design-drafts", orderId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"file_ids\":[" + firstFileId
                                + "],\"submission_key\":\"reject-v1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andReturn();
        long firstDraftId = extractId(firstUpload, "draft_id");

        mockMvc.perform(post("/orders/{orderId}/design-drafts/{draftId}/submit", orderId, firstDraftId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_USER_ID))
                .andExpect(status().isOk());
        mockMvc.perform(post("/orders/{orderId}/design-drafts/{draftId}/internal-review", orderId, firstDraftId)
                        .header("Authorization", "Bearer " + internalReviewerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\",\"internal_reject_reason\":\"边缘不清晰，请重新设计\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INTERNAL_REJECTED"))
                .andExpect(jsonPath("$.data.internal_reject_reason").value("边缘不清晰，请重新设计"));
        assertThat(notificationCount("DESIGN_DRAFT_INTERNAL_REJECTED", "WORKER")).isEqualTo(1L);

        mockMvc.perform(get("/orders/{orderId}/design-drafts", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        long secondFileId = createDesignDraftFile("doctor-reject.stl", "application/sla", 2048);
        MvcResult secondUpload = mockMvc.perform(post("/orders/{orderId}/design-drafts", orderId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"file_ids\":[" + secondFileId
                                + "],\"submission_key\":\"reject-v2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andReturn();
        long secondDraftId = extractId(secondUpload, "draft_id");
        mockMvc.perform(post("/orders/{orderId}/design-drafts/{draftId}/submit", orderId, secondDraftId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_USER_ID))
                .andExpect(status().isOk());
        mockMvc.perform(post("/orders/{orderId}/design-drafts/{draftId}/internal-review", orderId, secondDraftId)
                        .header("Authorization", "Bearer " + internalReviewerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/orders/{orderId}/design-drafts/{draftId}/doctor-confirm", orderId, secondDraftId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\",\"doctor_reject_reason\":\"咬合面需要调整\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DOCTOR_REJECTED"))
                .andExpect(jsonPath("$.data.doctor_reject_reason").value("咬合面需要调整"));
        assertThat(notificationCount("DESIGN_DRAFT_DOCTOR_REJECTED", "INTERNAL")).isEqualTo(1L);

        long thirdFileId = createDesignDraftFile("version-three.stl", "application/sla", 3072);
        mockMvc.perform(post("/orders/{orderId}/design-drafts", orderId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"file_ids\":[" + thirdFileId
                                + "],\"submission_key\":\"reject-v3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(3));
    }

    private long createDesignDraftFile(String filename, String contentType, long fileSize) {
        jdbcClient.sql("""
                        INSERT INTO file_resource
                            (order_id, owner_user_id, source_type, visibility, bucket_name,
                             object_key, original_filename, content_type, file_size, upload_status)
                        VALUES
                            (:orderId, :ownerUserId, 'DESIGN_DRAFT', 'INTERNAL', 'ai-order-private',
                             :objectKey, :filename, :contentType, :fileSize, 'COMPLETED')
                        """)
                .param("orderId", orderId)
                .param("ownerUserId", WORKER_USER_ID)
                .param("objectKey", "test/collab/" + UUID.randomUUID() + "/" + filename)
                .param("filename", filename)
                .param("contentType", contentType)
                .param("fileSize", fileSize)
                .update();
        return jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    private void createClaimedDesignTask() {
        jdbcClient.sql("""
                        INSERT INTO design_task
                            (order_id, node_instance_id, task_status, assigned_user_id, claimed_at)
                        VALUES
                            (
                                :orderId,
                                (
                                    SELECT node.node_instance_id
                                    FROM order_process_node node
                                    JOIN order_process_instance instance
                                      ON instance.instance_id = node.instance_id
                                    WHERE instance.order_id = :orderId
                                      AND node.node_category = 'DESIGN_GATE'
                                    ORDER BY node.node_instance_id
                                    LIMIT 1
                                ),
                                'CLAIMED',
                                :workerUserId,
                                CURRENT_TIMESTAMP(3)
                            )
                        """)
                .param("orderId", orderId)
                .param("workerUserId", WORKER_USER_ID)
                .update();
    }

    private void markFinalOutCheckPassed() {
        long finalNodeId = latestOrderNodeId();
        jdbcClient.sql("""
                        UPDATE order_process_node
                        SET node_status = 'COMPLETED'
                        WHERE node_instance_id = :nodeInstanceId
                        """)
                .param("nodeInstanceId", finalNodeId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO check_record
                            (order_id, node_instance_id, check_type, result, checker_user_id, note)
                        VALUES
                            (:orderId, :nodeInstanceId, 'OUT', 'PASS', :checkerUserId, '终检通过')
                        """)
                .param("orderId", orderId)
                .param("nodeInstanceId", finalNodeId)
                .param("checkerUserId", WORKER_USER_ID)
                .update();
    }

    private void markPaymentNotRequired() {
        jdbcClient.sql("""
                        INSERT INTO order_bill (order_id, bill_status, payment_status)
                        VALUES (:orderId, 'PENDING', 'NOT_REQUIRED')
                        ON DUPLICATE KEY UPDATE payment_status = 'NOT_REQUIRED'
                        """)
                .param("orderId", orderId)
                .update();
    }

    private long latestOrderNodeId() {
        return jdbcClient.sql("""
                        SELECT n.node_instance_id
                        FROM order_process_node n
                        JOIN order_process_instance i ON i.instance_id = n.instance_id
                        WHERE i.order_id = :orderId
                        ORDER BY n.step_order DESC, n.node_instance_id DESC
                        LIMIT 1
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
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
                            (:instanceId, :sourceNodeId, :nodeCode, '协同DataScope节点', 1,
                             0, 'PRODUCTION', 0, 0, 'READY', :workerUserId)
                        """)
                .param("instanceId", instanceId)
                .param("sourceNodeId", sourceNodeId)
                .param("nodeCode", "collab-datascope-" + suffix.substring(0, 12))
                .param("workerUserId", WORKER_USER_ID)
                .update();
        jdbcClient.sql("""
                        INSERT INTO order_process_node
                            (instance_id, source_node_id, node_code, process_name, stage_name, step_order,
                             is_optional, node_category, need_in_check, need_out_check, node_status)
                        VALUES
                            (:instanceId, :sourceNodeId, :nodeCode, '设计稿确认', '设计审核', -10,
                             0, 'DESIGN_GATE', 0, 0, 'READY')
                        """)
                .param("instanceId", instanceId)
                .param("sourceNodeId", sourceNodeId)
                .param("nodeCode", "collab-design-gate-" + suffix.substring(0, 12))
                .update();
    }

    private long extractId(MvcResult result, String fieldName) throws Exception {
        String content = result.getResponse().getContentAsString();
        int fieldStart = content.indexOf("\"" + fieldName + "\":");
        assertThat(fieldStart).isGreaterThanOrEqualTo(0);
        int valueStart = fieldStart + fieldName.length() + 3;
        int valueEnd = valueStart;
        while (valueEnd < content.length() && Character.isDigit(content.charAt(valueEnd))) {
            valueEnd++;
        }
        return Long.parseLong(content.substring(valueStart, valueEnd));
    }
}
