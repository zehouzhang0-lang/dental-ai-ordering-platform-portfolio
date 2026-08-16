package com.yuri.aiorder.design;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.BearerTokenService;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DesignTaskCollaborationTests {

    private static final long ADMIN_USER_ID = 8001L;
    private static final long CS_USER_ID = 8002L;
    private static final long WORKER_USER_ID = 9601L;
    private static final long OTHER_WORKER_USER_ID = 1996202L;
    private static final long DOCTOR_USER_ID = 1999701L;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BearerTokenService tokenService;

    private long clinicId;
    private long orderId;
    private long chainId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        ensureUser(OTHER_WORKER_USER_ID, "design-worker-" + suffix, "WORKER");
        ensureUser(DOCTOR_USER_ID, "design-doctor-" + suffix, "DOCTOR");

        jdbcClient.sql("INSERT INTO clinic (clinic_name) VALUES (:clinicName)")
                .param("clinicName", "二期设计协同诊所-" + suffix)
                .update();
        clinicId = lastInsertId();

        jdbcClient.sql("""
                        INSERT INTO orders
                            (order_no, clinic_id, doctor_user_id, cs_user_id, product_type,
                             internal_status, external_status)
                        VALUES
                            (:orderNo, :clinicId, :doctorUserId, :csUserId, 'DESIGN_FLOW_TEST',
                             'PENDING_PRODUCTION_REVIEW', 'PENDING_REVIEW')
                        """)
                .param("orderNo", "D2" + suffix.substring(0, 12))
                .param("clinicId", clinicId)
                .param("doctorUserId", DOCTOR_USER_ID)
                .param("csUserId", CS_USER_ID)
                .update();
        orderId = lastInsertId();
        chainId = createWorkflowChain(suffix);
    }

    @Test
    void productionReviewCreatesExactlyOneOpenDesignTask() throws Exception {
        mockMvc.perform(post("/orders/{orderId}/production-review", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productionReviewBody()))
                .andExpect(status().isForbidden());

        reviewProduction()
                .andExpect(jsonPath("$.data.internal_status").value("IN_DESIGN"))
                .andExpect(jsonPath("$.data.external_status").value("DESIGNING"));

        long taskId = taskId();
        assertThat(taskId).isPositive();
        assertThat(taskStatus()).isEqualTo("OPEN");
        assertThat(taskCount()).isEqualTo(1L);
        assertThat(eventCount(taskId, "TASK_CREATED")).isEqualTo(1L);

        mockMvc.perform(post("/orders/{orderId}/production-review", orderId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", ADMIN_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productionReviewBody()))
                .andExpect(status().isConflict());

        assertThat(taskCount()).isEqualTo(1L);
        assertThat(eventCount(taskId, "TASK_CREATED")).isEqualTo(1L);
    }

    @Test
    void existingOrderWithoutDesignTaskReturnsEmptyDraftListForAuthorizedReaders() throws Exception {
        mockMvc.perform(get("/orders/{orderId}/design-drafts", orderId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", ADMIN_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        mockMvc.perform(get("/orders/{orderId}/design-drafts", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        mockMvc.perform(get("/orders/{orderId}/design-drafts", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        mockMvc.perform(get("/orders/{orderId}/design-drafts", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID + 1)
                        .header("X-Bootstrap-Clinic-Id", clinicId + 1))
                .andExpect(status().isForbidden());
    }

    @Test
    void workerClaimRemovesTaskFromPoolAndRejectsEveryLaterClaim() throws Exception {
        reviewProduction();
        long taskId = taskId();

        assertThat(taskIds(getJson("/design-tasks/pool", WORKER_USER_ID))).contains(taskId);

        claim(taskId, WORKER_USER_ID)
                .andExpect(jsonPath("$.data.status").value("CLAIMED"))
                .andExpect(jsonPath("$.data.assigned_user_id")
                        .value(Long.toString(WORKER_USER_ID)));

        assertThat(taskIds(getJson("/design-tasks/pool", WORKER_USER_ID))).doesNotContain(taskId);
        assertThat(taskIds(getJson("/design-tasks/mine", WORKER_USER_ID))).contains(taskId);

        claim(taskId, OTHER_WORKER_USER_ID).andExpect(status().isConflict());
        claim(taskId, WORKER_USER_ID).andExpect(status().isConflict());
        assertThat(eventCount(taskId, "CLAIM")).isEqualTo(1L);
    }

    @Test
    void draftUploadIsAssigneeOnlyIdempotentMultiFileAndSeparateFromSubmit() throws Exception {
        reviewProduction();
        long taskId = taskId();
        claim(taskId, WORKER_USER_ID);
        long firstFileId = createDraftFile(WORKER_USER_ID, "design-v1.stl");
        long secondFileId = createDraftFile(WORKER_USER_ID, "design-v1.pdf");

        uploadDraft(
                OTHER_WORKER_USER_ID,
                "submission-v1",
                "V1 多文件",
                firstFileId,
                secondFileId).andExpect(status().isForbidden());

        MvcResult created = uploadDraft(
                        WORKER_USER_ID,
                        "submission-v1",
                        "V1 多文件",
                        firstFileId,
                        secondFileId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.uploader_user_id")
                        .value(Long.toString(WORKER_USER_ID)))
                .andExpect(jsonPath("$.data.review_history[0].actor_user_id")
                        .value(Long.toString(WORKER_USER_ID)))
                .andExpect(jsonPath("$.data.review_history[0].from_assignee_user_id")
                        .value(Long.toString(WORKER_USER_ID)))
                .andExpect(jsonPath("$.data.review_history[0].to_assignee_user_id")
                        .value(Long.toString(WORKER_USER_ID)))
                .andExpect(jsonPath("$.data.file_ids[0]").value(firstFileId))
                .andExpect(jsonPath("$.data.file_ids[1]").value(secondFileId))
                .andExpect(jsonPath("$.data.file_count").value(2))
                .andExpect(jsonPath("$.data.submitted_at").doesNotExist())
                .andReturn();
        long draftId = responseId(created, "draft_id");

        assertThat(taskStatus()).isEqualTo("CLAIMED");
        assertThat(draftCount()).isEqualTo(1L);
        assertThat(draftFileCount(draftId)).isEqualTo(2L);

        uploadDraft(
                        WORKER_USER_ID,
                        "submission-v1",
                        "V1 多文件",
                        firstFileId,
                        secondFileId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft_id").value(draftId));
        assertThat(draftCount()).isEqualTo(1L);

        uploadDraft(
                        WORKER_USER_ID,
                        "submission-v1",
                        "不同 payload",
                        firstFileId)
                .andExpect(status().isConflict());
        assertThat(draftCount()).isEqualTo(1L);

        mockMvc.perform(post("/orders/{orderId}/design-drafts/{draftId}/internal-review", orderId, draftId)
                        .header("Authorization", "Bearer " + internalReviewerToken(WORKER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isConflict());

        submitDraft(draftId, WORKER_USER_ID)
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.submitted_at").isString());
        assertThat(taskStatus()).isEqualTo("SUBMITTED");

        uploadDraft(
                        WORKER_USER_ID,
                        "submission-v1",
                        "V1 多文件",
                        firstFileId,
                        secondFileId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft_id").value(draftId))
                .andExpect(jsonPath("$.data.submitted_at").isString());
        assertThat(taskStatus()).isEqualTo("SUBMITTED");
        assertThat(draftCount()).isEqualTo(1L);
    }

    @Test
    void internalReviewRequiresPermissionRejectReasonAndKeepsRejectedDraftInternal() throws Exception {
        reviewProduction();
        long taskId = taskId();
        claim(taskId, WORKER_USER_ID);
        long firstFileId = createDraftFile(WORKER_USER_ID, "internal-reject-v1.stl");
        long firstDraftId = createAndSubmitDraft(
                WORKER_USER_ID, "reject-v1", "等待内部审核", firstFileId);

        internalReview(firstDraftId, "WORKER", OTHER_WORKER_USER_ID, "{\"action\":\"APPROVE\"}")
                .andExpect(status().isForbidden());
        internalReview(firstDraftId, "CS", CS_USER_ID, "{\"action\":\"APPROVE\"}")
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/orders/{orderId}/design-drafts/{draftId}/cs-review", orderId, firstDraftId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isForbidden());
        internalReview(firstDraftId, "ADMIN", ADMIN_USER_ID, "{\"action\":\"REJECT\"}")
                .andExpect(status().isForbidden());
        internalReview(firstDraftId, "REVIEWER", WORKER_USER_ID, "{\"action\":\"REJECT\"}")
                .andExpect(status().isBadRequest());

        internalReview(
                        firstDraftId,
                        "REVIEWER",
                        WORKER_USER_ID,
                        "{\"action\":\"REJECT\",\"internal_reject_reason\":\"边缘线不连续\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INTERNAL_REJECTED"))
                .andExpect(jsonPath("$.data.internal_reject_reason").value("边缘线不连续"));
        assertThat(taskStatus()).isEqualTo("INTERNAL_REJECTED");
        assertThat(fileVisibility(firstFileId)).isEqualTo("INTERNAL");
        assertThat(eventReason(taskId, "INTERNAL_REJECT")).isEqualTo("边缘线不连续");

        mockMvc.perform(get("/orders/{orderId}/design-drafts", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        long secondFileId = createDraftFile(WORKER_USER_ID, "internal-reject-v2.stl");
        uploadDraft(WORKER_USER_ID, "reject-v2", "按意见修订", secondFileId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
    }

    @Test
    void bearerReloadsDirectWorkerReviewPermissionWithoutRelogin() throws Exception {
        removeDirectReviewPermission(WORKER_USER_ID);
        reviewProduction();
        claim(taskId(), WORKER_USER_ID);
        long fileId = createDraftFile(WORKER_USER_ID, "leader-review.stl");
        long draftId = createAndSubmitDraft(
                WORKER_USER_ID, "leader-review", "组长审核", fileId);
        String token = tokenService.issue(new BootstrapIdentity(
                UserRole.WORKER,
                WORKER_USER_ID,
                null,
                "worker",
                Set.of("design-task:claim", "design-task:operate-self"),
                "SELF"));

        mockMvc.perform(get("/design-tasks/internal-review-queue")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        grantDirectReviewPermission(WORKER_USER_ID);

        mockMvc.perform(get("/design-tasks/internal-review-queue")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"task_id\":" + taskId())));
        mockMvc.perform(post("/orders/{orderId}/design-drafts/{draftId}/internal-review", orderId, draftId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_DOCTOR"))
                .andExpect(jsonPath("$.data.doctor_visible_at").isString());

        assertThat(fileVisibility(fileId)).isEqualTo("DOCTOR_CS");
        mockMvc.perform(get("/orders/{orderId}/design-drafts", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].draft_id").value(draftId))
                .andExpect(jsonPath("$.data[0].status").value("PENDING_DOCTOR"))
                .andExpect(jsonPath("$.data[0].uploader_user_id").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].submission_key").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].internal_reject_reason").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(get("/orders/{orderId}/design-task", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assigned_user_id").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.assigned_user_name").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(content().string(not(containsString("\"submission_key\":\"leader-review\""))));
        mockMvc.perform(get("/orders/{orderId}/design-task", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assigned_user_id").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.latest_draft.uploader_user_id")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.latest_draft.submission_key")
                        .value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void doctorAndCsExternalProgressHideInternalDraftAndTaskFacts() throws Exception {
        reviewProduction();
        claim(taskId(), WORKER_USER_ID);
        long rejectedFileId = createDraftFile(WORKER_USER_ID, "external-hidden-v1.stl");
        long rejectedDraftId = createAndSubmitDraft(
                WORKER_USER_ID,
                "internal-secret-key",
                "内部上传备注不可外发",
                rejectedFileId);
        internalReview(
                        rejectedDraftId,
                        "REVIEWER",
                        WORKER_USER_ID,
                        "{\"action\":\"REJECT\",\"internal_reject_reason\":\"内部技术驳回原因\"}")
                .andExpect(status().isOk());

        assertExternalTaskHasNoInternalFacts(
                "DOCTOR", DOCTOR_USER_ID, clinicId, "DESIGNING", true);
        assertExternalTaskHasNoInternalFacts(
                "CS", CS_USER_ID, null, "DESIGNING", true);

        long approvedFileId = createDraftFile(WORKER_USER_ID, "external-visible-v2.stl");
        long approvedDraftId = createAndSubmitDraft(
                WORKER_USER_ID,
                "approved-secret-key",
                "第二版内部上传备注",
                approvedFileId);
        internalReview(approvedDraftId, "REVIEWER", WORKER_USER_ID, "{\"action\":\"APPROVE\"}")
                .andExpect(status().isOk());

        assertExternalTaskHasNoInternalFacts(
                "DOCTOR", DOCTOR_USER_ID, clinicId, "PENDING_DOCTOR", false);
        assertExternalTaskHasNoInternalFacts(
                "CS", CS_USER_ID, null, "PENDING_DOCTOR", false);

        doctorReview(
                        approvedDraftId,
                        "DOCTOR",
                        DOCTOR_USER_ID,
                        clinicId,
                        "{\"action\":\"REJECT\",\"doctor_reject_reason\":\"医生可见修改意见\"}")
                .andExpect(status().isOk());

        mockMvc.perform(get("/orders/{orderId}/design-task", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DOCTOR_REJECTED"))
                .andExpect(jsonPath("$.data.review_history.length()").value(1))
                .andExpect(jsonPath("$.data.review_history[0].event_type").value("DOCTOR_REJECT"))
                .andExpect(jsonPath("$.data.review_history[0].actor_user_id")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.review_history[0].actor_role")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.review_history[0].from_assignee_user_id")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.review_history[0].reason").value("医生可见修改意见"))
                .andExpect(content().string(not(containsString("INTERNAL_APPROVE"))))
                .andExpect(content().string(not(containsString("内部技术驳回原因"))));
    }

    @Test
    void doctorCannotConfirmWhenApprovedDraftFileBecomesInvalid() throws Exception {
        reviewProduction();
        long taskId = taskId();
        claim(taskId, WORKER_USER_ID);
        long fileId = createDraftFile(WORKER_USER_ID, "doctor-confirm-invalid-file.stl");
        long draftId = createAndSubmitDraft(
                WORKER_USER_ID,
                "doctor-confirm-invalid-file",
                "内审通过后文件失效",
                fileId);
        internalReview(draftId, "REVIEWER", WORKER_USER_ID, "{\"action\":\"APPROVE\"}")
                .andExpect(status().isOk());

        jdbcClient.sql("""
                        UPDATE file_resource
                        SET status = 'DELETED',
                            visibility = 'INTERNAL'
                        WHERE file_id = :fileId
                        """)
                .param("fileId", fileId)
                .update();

        doctorReview(
                        draftId,
                        "DOCTOR",
                        DOCTOR_USER_ID,
                        clinicId,
                        "{\"action\":\"CONFIRM\"}")
                .andExpect(status().isConflict());

        assertThat(taskStatus()).isEqualTo("PENDING_DOCTOR");
        assertThat(jdbcClient.sql("""
                        SELECT draft_status
                        FROM design_draft
                        WHERE design_draft_id = :draftId
                        """)
                .param("draftId", draftId)
                .query(String.class)
                .single()).isEqualTo("PENDING_DOCTOR");
        assertThat(eventCount(taskId, "DOCTOR_CONFIRM")).isZero();
    }

    @Test
    void adminManageListsOpenAndClaimedTasksWhileOtherRolesAreForbidden() throws Exception {
        reviewProduction();
        long taskId = taskId();

        JsonNode openTask = taskById(
                getJson("/design-tasks/manage", "ADMIN", ADMIN_USER_ID),
                taskId);
        assertThat(openTask.path("status").asText()).isEqualTo("OPEN");

        claim(taskId, WORKER_USER_ID).andExpect(status().isOk());
        JsonNode claimedTask = taskById(
                getJson("/design-tasks/manage", "ADMIN", ADMIN_USER_ID),
                taskId);
        assertThat(claimedTask.path("status").asText()).isEqualTo("CLAIMED");
        assertThat(claimedTask.path("assigned_user_id").asText())
                .isEqualTo(Long.toString(WORKER_USER_ID));

        mockMvc.perform(get("/design-tasks/manage")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/design-tasks/manage")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_USER_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    void v50CompatibilityMigrationIsInstalledAndKeepsBothRepairRules() throws Exception {
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM flyway_schema_history
                        WHERE version = '50'
                          AND success = 1
                        """)
                .query(Long.class)
                .single()).isEqualTo(1L);

        String migration = new ClassPathResource(
                        "db/migration/V50__phase_two_design_legacy_compatibility.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(migration)
                .contains("d.doctor_visible_at IS NOT NULL")
                .contains("SET f.visibility = 'DOCTOR_CS'")
                .contains("dt.task_status IN ('CLAIMED', 'INTERNAL_REJECTED', 'DOCTOR_REJECTED')")
                .contains("dt.task_status = 'OPEN'");
    }

    @Test
    void doctorRejectsVisibleDraftCanReceiveRevisionAndOnlyDoctorCanFinallyConfirm() throws Exception {
        reviewProduction();
        claim(taskId(), WORKER_USER_ID);
        long firstFileId = createDraftFile(WORKER_USER_ID, "doctor-reject-v1.stl");
        long firstDraftId = createAndSubmitDraft(
                WORKER_USER_ID, "doctor-reject-v1", "首版", firstFileId);
        internalReview(firstDraftId, "REVIEWER", WORKER_USER_ID, "{\"action\":\"APPROVE\"}")
                .andExpect(status().isOk());

        doctorReview(firstDraftId, "ADMIN", ADMIN_USER_ID, null, "{\"action\":\"CONFIRM\"}")
                .andExpect(status().isForbidden());
        doctorReview(
                        firstDraftId,
                        "DOCTOR",
                        DOCTOR_USER_ID,
                        clinicId,
                        "{\"action\":\"REJECT\",\"doctor_reject_reason\":\"调整咬合面\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DOCTOR_REJECTED"))
                .andExpect(jsonPath("$.data.doctor_reject_reason").value("调整咬合面"));

        long secondFileId = createDraftFile(WORKER_USER_ID, "doctor-reject-v2.stl");
        long secondDraftId = createAndSubmitDraft(
                WORKER_USER_ID, "doctor-reject-v2", "医生意见修订", secondFileId);
        internalReview(secondDraftId, "REVIEWER", WORKER_USER_ID, "{\"action\":\"APPROVE\"}")
                .andExpect(status().isOk());
        doctorReview(
                        secondDraftId,
                        "DOCTOR",
                        DOCTOR_USER_ID,
                        clinicId,
                        "{\"action\":\"CONFIRM\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DOCTOR_CONFIRMED"));

        mockMvc.perform(get("/orders/{orderId}/design-drafts", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].status").value("DOCTOR_REJECTED"))
                .andExpect(jsonPath("$.data[1].status").value("DOCTOR_CONFIRMED"));
    }

    @Test
    void adminTransferRequiresReasonWritesAuditAndProductionStartWaitsForDoctor() throws Exception {
        reviewProduction();
        long taskId = taskId();
        claim(taskId, WORKER_USER_ID);

        transfer(taskId, OTHER_WORKER_USER_ID, "")
                .andExpect(status().isBadRequest());
        transfer(taskId, OTHER_WORKER_USER_ID, "原领取人请假")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assigned_user_id")
                        .value(Long.toString(OTHER_WORKER_USER_ID)));
        assertThat(eventReason(taskId, "TRANSFER")).isEqualTo("原领取人请假");
        assertThat(eventTargetAssignee(taskId, "TRANSFER")).isEqualTo(OTHER_WORKER_USER_ID);

        long nodeId = firstNodeId();
        assignNode(nodeId, OTHER_WORKER_USER_ID);
        startNode(nodeId, OTHER_WORKER_USER_ID).andExpect(status().isConflict());

        long fileId = createDraftFile(OTHER_WORKER_USER_ID, "transferred-worker.stl");
        uploadDraft(
                WORKER_USER_ID,
                "former-worker",
                "原领取人不可上传",
                fileId).andExpect(status().isForbidden());
        long draftId = createAndSubmitDraft(
                OTHER_WORKER_USER_ID, "transferred-worker", "转派后提交", fileId);
        internalReview(draftId, "REVIEWER", WORKER_USER_ID, "{\"action\":\"APPROVE\"}")
                .andExpect(status().isOk());
        startNode(nodeId, OTHER_WORKER_USER_ID).andExpect(status().isConflict());

        doctorReview(
                        draftId,
                        "DOCTOR",
                        DOCTOR_USER_ID,
                        clinicId,
                        "{\"action\":\"CONFIRM\"}")
                .andExpect(status().isOk());
        startNode(nodeId, OTHER_WORKER_USER_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.node_status").value("IN_PROGRESS"));
    }

    private org.springframework.test.web.servlet.ResultActions reviewProduction() throws Exception {
        return mockMvc.perform(post("/orders/{orderId}/production-review", orderId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", ADMIN_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productionReviewBody()))
                .andExpect(status().isOk());
    }

    private String productionReviewBody() {
        return """
                {"action":"APPROVE","chain_id":%d,"intake_branch":"SCAN"}
                """.formatted(chainId);
    }

    private org.springframework.test.web.servlet.ResultActions claim(long taskId, long workerUserId) throws Exception {
        return mockMvc.perform(post("/design-tasks/{taskId}/claim", taskId)
                .header("X-Bootstrap-Role", "WORKER")
                .header("X-Bootstrap-User-Id", workerUserId));
    }

    private org.springframework.test.web.servlet.ResultActions uploadDraft(
            long workerUserId,
            String submissionKey,
            String note,
            long... fileIds) throws Exception {
        String files = java.util.Arrays.stream(fileIds)
                .mapToObj(String::valueOf)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        String body = """
                {"file_ids":[%s],"upload_note":"%s","submission_key":"%s"}
                """.formatted(files, note, submissionKey);
        return mockMvc.perform(post("/orders/{orderId}/design-drafts", orderId)
                .header("X-Bootstrap-Role", "WORKER")
                .header("X-Bootstrap-User-Id", workerUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions submitDraft(long draftId, long workerUserId)
            throws Exception {
        return mockMvc.perform(post("/orders/{orderId}/design-drafts/{draftId}/submit", orderId, draftId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId))
                .andExpect(status().isOk());
    }

    private long createAndSubmitDraft(long workerUserId, String key, String note, long... fileIds)
            throws Exception {
        MvcResult upload = uploadDraft(workerUserId, key, note, fileIds)
                .andExpect(status().isOk())
                .andReturn();
        long draftId = responseId(upload, "draft_id");
        submitDraft(draftId, workerUserId);
        return draftId;
    }

    private org.springframework.test.web.servlet.ResultActions internalReview(
            long draftId, String role, long userId, String body) throws Exception {
        var request = post("/orders/{orderId}/design-drafts/{draftId}/internal-review", orderId, draftId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if ("REVIEWER".equals(role)) {
            request.header("Authorization", "Bearer " + internalReviewerToken(userId));
        } else {
            request.header("X-Bootstrap-Role", role)
                    .header("X-Bootstrap-User-Id", userId);
        }
        return mockMvc.perform(request);
    }

    private String internalReviewerToken(long userId) {
        return tokenService.issue(new BootstrapIdentity(
                UserRole.WORKER,
                userId,
                null,
                null,
                Set.of("design-draft:internal-review", "workflow:read-internal"),
                "SELF"));
    }

    private org.springframework.test.web.servlet.ResultActions doctorReview(
            long draftId, String role, long userId, Long targetClinicId, String body) throws Exception {
        var request = post("/orders/{orderId}/design-drafts/{draftId}/doctor-confirm", orderId, draftId)
                .header("X-Bootstrap-Role", role)
                .header("X-Bootstrap-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (targetClinicId != null) {
            request.header("X-Bootstrap-Clinic-Id", targetClinicId);
        }
        return mockMvc.perform(request);
    }

    private org.springframework.test.web.servlet.ResultActions transfer(
            long taskId, long targetWorkerUserId, String reason) throws Exception {
        return mockMvc.perform(post("/design-tasks/{taskId}/transfer", taskId)
                .header("X-Bootstrap-Role", "ADMIN")
                .header("X-Bootstrap-User-Id", ADMIN_USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"new_user_id":%d,"reason":"%s"}
                        """.formatted(targetWorkerUserId, reason)));
    }

    private org.springframework.test.web.servlet.ResultActions startNode(long nodeId, long workerUserId)
            throws Exception {
        return mockMvc.perform(post("/process-instance/nodes/{nodeInstanceId}/start", nodeId)
                .header("X-Bootstrap-Role", "WORKER")
                .header("X-Bootstrap-User-Id", workerUserId));
    }

    private void assignNode(long nodeId, long workerUserId) throws Exception {
        mockMvc.perform(post("/orders/{orderId}/process-instance/assign", orderId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", ADMIN_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assignments":[{"node_instance_id":%d,"user_id":%d}]}
                                """.formatted(nodeId, workerUserId)))
                .andExpect(status().isOk());
    }

    private void assertExternalTaskHasNoInternalFacts(
            String role,
            long userId,
            Long targetClinicId,
            String expectedStatus,
            boolean expectNoDrafts) throws Exception {
        var request = get("/orders/{orderId}/design-task", orderId)
                .header("X-Bootstrap-Role", role)
                .header("X-Bootstrap-User-Id", userId);
        if (targetClinicId != null) {
            request.header("X-Bootstrap-Clinic-Id", targetClinicId);
        }
        var result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(expectedStatus))
                .andExpect(jsonPath("$.data.assigned_user_id")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.assigned_user_name")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.review_history").isEmpty())
                .andExpect(content().string(not(containsString("内部上传备注不可外发"))))
                .andExpect(content().string(not(containsString("内部技术驳回原因"))))
                .andExpect(content().string(not(containsString("internal-secret-key"))))
                .andExpect(content().string(not(containsString("approved-secret-key"))))
                .andExpect(content().string(not(containsString("INTERNAL_REJECT"))))
                .andExpect(content().string(not(containsString("INTERNAL_APPROVE"))))
                .andExpect(content().string(not(containsString("\"uploader_user_id\":9601"))));
        if (expectNoDrafts) {
            result.andExpect(jsonPath("$.data.latest_draft")
                            .value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.data.drafts").isEmpty());
        } else {
            result.andExpect(jsonPath("$.data.latest_draft.status").value("PENDING_DOCTOR"))
                    .andExpect(jsonPath("$.data.latest_draft.upload_note")
                            .value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.data.latest_draft.uploader_user_id")
                            .value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.data.latest_draft.submission_key")
                            .value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.data.latest_draft.internal_reject_reason")
                            .value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.data.latest_draft.cs_reject_reason")
                            .value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.data.latest_draft.review_history").isEmpty());
        }
    }

    private JsonNode getJson(String path, long workerUserId) throws Exception {
        return getJson(path, "WORKER", workerUserId);
    }

    private JsonNode getJson(String path, String role, long userId) throws Exception {
        MvcResult result = mockMvc.perform(get(path)
                        .header("X-Bootstrap-Role", role)
                        .header("X-Bootstrap-User-Id", userId))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private JsonNode taskById(JsonNode data, long taskId) {
        return StreamSupport.stream(data.spliterator(), false)
                .filter(node -> node.path("task_id").asLong() == taskId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing task_id=" + taskId));
    }

    private Set<Long> taskIds(JsonNode data) {
        return StreamSupport.stream(data.spliterator(), false)
                .map(node -> node.path("task_id").asLong())
                .collect(java.util.stream.Collectors.toSet());
    }

    private long createDraftFile(long ownerUserId, String filename) {
        jdbcClient.sql("""
                        INSERT INTO file_resource
                            (order_id, owner_user_id, source_type, visibility, bucket_name,
                             object_key, original_filename, content_type, file_size, upload_status)
                        VALUES
                            (:orderId, :ownerUserId, 'DESIGN_DRAFT', 'INTERNAL', 'ai-order-test-private',
                             :objectKey, :filename, 'application/octet-stream', 1024, 'COMPLETED')
                        """)
                .param("orderId", orderId)
                .param("ownerUserId", ownerUserId)
                .param("objectKey", "test/phase-two-design/" + UUID.randomUUID() + "/" + filename)
                .param("filename", filename)
                .update();
        return lastInsertId();
    }

    private long createWorkflowChain(String suffix) {
        jdbcClient.sql("""
                        INSERT INTO workflow_chain
                            (chain_code, chain_name, product_type, version, intake_branch, status)
                        VALUES
                            (:chainCode, :chainName, 'DESIGN_FLOW_TEST', 1, 'BOTH', 1)
                        """)
                .param("chainCode", "design_flow_" + suffix)
                .param("chainName", "二期设计协同链-" + suffix)
                .update();
        long createdChainId = lastInsertId();
        jdbcClient.sql("""
                        INSERT INTO workflow_node
                            (chain_id, node_code, process_name, step_order, is_optional,
                             node_category, need_in_check, need_out_check)
                        VALUES
                            (:chainId, 'PRODUCTION_START', '生产首节点', 10, 0,
                             'PRODUCTION', 0, 0)
                        """)
                .param("chainId", createdChainId)
                .update();
        return createdChainId;
    }

    private void ensureUser(long userId, String username, String roleCode) {
        jdbcClient.sql("""
                        INSERT INTO system_user
                            (user_id, username, password_hash, display_name, user_type, status)
                        VALUES
                            (:userId, :username, 'test-password-hash', :username, :roleCode, 'ACTIVE')
                        ON DUPLICATE KEY UPDATE
                            username = VALUES(username),
                            display_name = VALUES(display_name),
                            user_type = VALUES(user_type),
                            status = 'ACTIVE'
                        """)
                .param("userId", userId)
                .param("username", username)
                .param("roleCode", roleCode)
                .update();
        jdbcClient.sql("""
                        INSERT IGNORE INTO system_user_role (user_id, role_id)
                        SELECT :userId, role_id
                        FROM system_role
                        WHERE role_code = :roleCode
                        """)
                .param("userId", userId)
                .param("roleCode", roleCode)
                .update();
    }

    private void removeDirectReviewPermission(long userId) {
        jdbcClient.sql("""
                        DELETE up
                        FROM system_user_permission up
                        JOIN system_permission p ON p.permission_id = up.permission_id
                        WHERE up.user_id = :userId
                          AND p.permission_code = 'design-draft:internal-review'
                        """)
                .param("userId", userId)
                .update();
    }

    private void grantDirectReviewPermission(long userId) {
        jdbcClient.sql("""
                        INSERT INTO system_user_permission (user_id, permission_id)
                        SELECT :userId, permission_id
                        FROM system_permission
                        WHERE permission_code = 'design-draft:internal-review'
                        """)
                .param("userId", userId)
                .update();
    }

    private long taskId() {
        return jdbcClient.sql("SELECT design_task_id FROM design_task WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    private String taskStatus() {
        return jdbcClient.sql("SELECT task_status FROM design_task WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single();
    }

    private long taskCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM design_task WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    private long draftCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM design_draft WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    private long draftFileCount(long draftId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM design_draft_file WHERE design_draft_id = :draftId")
                .param("draftId", draftId)
                .query(Long.class)
                .single();
    }

    private String fileVisibility(long fileId) {
        return jdbcClient.sql("SELECT visibility FROM file_resource WHERE file_id = :fileId")
                .param("fileId", fileId)
                .query(String.class)
                .single();
    }

    private long eventCount(long taskId, String eventType) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM design_task_event
                        WHERE design_task_id = :taskId
                          AND event_type = :eventType
                        """)
                .param("taskId", taskId)
                .param("eventType", eventType)
                .query(Long.class)
                .single();
    }

    private String eventReason(long taskId, String eventType) {
        return jdbcClient.sql("""
                        SELECT reason
                        FROM design_task_event
                        WHERE design_task_id = :taskId
                          AND event_type = :eventType
                        ORDER BY event_id DESC
                        LIMIT 1
                        """)
                .param("taskId", taskId)
                .param("eventType", eventType)
                .query(String.class)
                .single();
    }

    private long eventTargetAssignee(long taskId, String eventType) {
        return jdbcClient.sql("""
                        SELECT to_assignee_user_id
                        FROM design_task_event
                        WHERE design_task_id = :taskId
                          AND event_type = :eventType
                        ORDER BY event_id DESC
                        LIMIT 1
                        """)
                .param("taskId", taskId)
                .param("eventType", eventType)
                .query(Long.class)
                .single();
    }

    private long firstNodeId() {
        return jdbcClient.sql("""
                        SELECT node.node_instance_id
                        FROM order_process_node node
                        JOIN order_process_instance instance
                          ON instance.instance_id = node.instance_id
                        WHERE instance.order_id = :orderId
                          AND node.node_category <> 'DESIGN_GATE'
                        ORDER BY node.step_order, node.node_instance_id
                        LIMIT 1
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    private long responseId(MvcResult result, String field) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path(field)
                .asLong();
    }

    private long lastInsertId() {
        return jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }
}
