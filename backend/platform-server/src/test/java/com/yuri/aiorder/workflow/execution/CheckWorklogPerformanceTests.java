package com.yuri.aiorder.workflow.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.BearerTokenService;
import java.time.LocalDate;
import java.util.List;
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
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CheckWorklogPerformanceTests {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BearerTokenService tokenService;

    private long orderId;
    private long chainId;
    private long nodeInstanceId;
    private long doctorUserId;
    private long csUserId;
    private long workerUserId;
    private long otherWorkerUserId;
    private long clinicId;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long userSeed = Long.parseLong(suffix.substring(0, 8), 16);
        doctorUserId = 990100000L + userSeed;
        csUserId = 990150000L + userSeed;
        workerUserId = 990200000L + userSeed;
        otherWorkerUserId = 990300000L + userSeed;
        clinicId = createClinic("执行测试诊所-" + suffix);
        ensureUser(doctorUserId, "execution-doctor-" + suffix, "DOCTOR", clinicId);
        ensureUser(csUserId, "execution-cs-" + suffix, "CS", null);
        ensureUser(workerUserId, "execution-worker-" + suffix, "WORKER", null);
        ensureUser(otherWorkerUserId, "execution-worker-alt-" + suffix, "WORKER", null);
        orderId = createOrder("EX" + suffix.substring(0, 12), clinicId);
        chainId = createOneNodeChain(suffix);
        nodeInstanceId = instantiateAndAssign();
    }

    @Test
    void nodeRequiresInCheckBeforeStartAndOutCheckRequiresCompletion() throws Exception {
        mockMvc.perform(get("/tasks/mine")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .param("status", "READY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].node_instance_id").value(nodeInstanceId))
                .andExpect(jsonPath("$.data[0].can_start").value(false))
                .andExpect(jsonPath("$.data[0].start_block_reason").value("IN_CHECK_REQUIRED"));

        mockMvc.perform(get("/orders/{orderId}/process-instance", orderId)
                        .header("X-Bootstrap-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[1].node_instance_id").value(nodeInstanceId))
                .andExpect(jsonPath("$.data.nodes[1].can_start").value(false))
                .andExpect(jsonPath("$.data.nodes[1].start_block_reason").value("IN_CHECK_REQUIRED"));

        mockMvc.perform(post("/process-instance/nodes/{nodeInstanceId}/start", nodeInstanceId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId))
                .andExpect(status().isConflict());

        submitCheck(nodeInstanceId, 1, true, null);

        mockMvc.perform(get("/tasks/mine")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .param("status", "READY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].can_start").value(true))
                .andExpect(jsonPath("$.data[0].start_block_reason").doesNotExist());

        mockMvc.perform(get("/orders/{orderId}/process-instance", orderId)
                        .header("X-Bootstrap-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[1].node_instance_id").value(nodeInstanceId))
                .andExpect(jsonPath("$.data.nodes[1].can_start").value(true))
                .andExpect(jsonPath("$.data.nodes[1].start_block_reason").doesNotExist());

        mockMvc.perform(post("/process-instance/nodes/{nodeInstanceId}/start", nodeInstanceId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.node_status").value("IN_PROGRESS"));

        mockMvc.perform(post("/check-records")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"node_instance_id":%d,"check_type":2,"is_pass":true}
                                """.formatted(nodeInstanceId)))
                .andExpect(status().isConflict());
    }

    @Test
    void failedOutCheckCreatesReworkAndNewWorkLogWithoutOverwritingOriginalPerformance() throws Exception {
        submitCheck(nodeInstanceId, 1, true, null);
        startNode(nodeInstanceId);
        long firstWorkLogId = startWorkLog(nodeInstanceId);
        makeWorkLogStartedMinutesAgo(firstWorkLogId, 10);
        pauseWorkLog(firstWorkLogId);
        makeOpenPauseStartedMinutesAgo(firstWorkLogId, 2);
        resumeWorkLog(firstWorkLogId);
        finishWorkLog(firstWorkLogId)
                .andExpect(jsonPath("$.data.effective_duration_seconds").value(480));
        completeNode(nodeInstanceId);

        submitCheck(nodeInstanceId, 2, false, nodeInstanceId);

        assertThat(reworkCount(orderId)).isEqualTo(1L);
        assertThat(nodeStatus(nodeInstanceId)).isEqualTo("READY");
        assertThat(workLogStatus(firstWorkLogId)).isEqualTo("COMPLETED");

        mockMvc.perform(get("/reworks")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .param("status", "PENDING")
                        .param("order_id", String.valueOf(orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].order_id").value(orderId))
                .andExpect(jsonPath("$.data[0].rework_id").isNumber())
                .andExpect(jsonPath("$.data[0].source_check_id").isNumber())
                .andExpect(jsonPath("$.data[0].from_node_instance_id").value(nodeInstanceId))
                .andExpect(jsonPath("$.data[0].target_node_instance_id").value(nodeInstanceId))
                .andExpect(jsonPath("$.data[0].target_node_status").value("READY"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].reason_detail").value("测试"));

        mockMvc.perform(get("/reworks")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", otherWorkerUserId)
                        .param("status", "PENDING")
                        .param("order_id", String.valueOf(orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        startNode(nodeInstanceId);
        long secondWorkLogId = startWorkLog(nodeInstanceId);

        assertThat(secondWorkLogId).isNotEqualTo(firstWorkLogId);
        assertThat(workLogCount(nodeInstanceId)).isEqualTo(2L);

        mockMvc.perform(get("/performance")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .param("user_id", String.valueOf(otherWorkerUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user_id").value(workerUserId))
                .andExpect(jsonPath("$.data.completed_count").value(1))
                .andExpect(jsonPath("$.data.effective_duration").value(8))
                .andExpect(jsonPath("$.data.rework_count").value(1));

        mockMvc.perform(get("/performance")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .param("user_id", String.valueOf(workerUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user_id").value(workerUserId))
                .andExpect(jsonPath("$.data.completed_count").value(1));
    }

    @Test
    void reworkCanCloseOnlyAfterTargetOutPassAndKeepsResponsibilityClassification() throws Exception {
        submitCheck(nodeInstanceId, 1, true, null);
        startNode(nodeInstanceId);
        completeNode(nodeInstanceId);
        long reworkId = submitCheck(nodeInstanceId, 2, false, nodeInstanceId).path("rework_id").asLong();

        mockMvc.perform(post("/reworks/{reworkId}/close", reworkId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason_category":"FIT_ISSUE",
                                  "responsibility_type":"WORKER",
                                  "close_note":"返工复检通过"
                                }
                                """))
                .andExpect(status().isConflict());

        startNode(nodeInstanceId);
        completeNode(nodeInstanceId);
        submitCheck(nodeInstanceId, 2, true, null);

        mockMvc.perform(post("/reworks/{reworkId}/close", reworkId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason_category":"FIT_ISSUE",
                                  "responsibility_type":"WORKER",
                                  "close_note":"返工复检通过"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rework_id").value(reworkId))
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.reason_category").value("FIT_ISSUE"))
                .andExpect(jsonPath("$.data.responsibility_type").value("WORKER"))
                .andExpect(jsonPath("$.data.close_note").value("返工复检通过"))
                .andExpect(jsonPath("$.data.closed_at").isNotEmpty());

        mockMvc.perform(get("/reworks")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .param("status", "DONE")
                        .param("order_id", String.valueOf(orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].rework_id").value(reworkId))
                .andExpect(jsonPath("$.data[0].reason_category").value("FIT_ISSUE"))
                .andExpect(jsonPath("$.data[0].responsibility_type").value("WORKER"));
    }

    @Test
    void reworkCloseUsesServerDictionaryAndRejectsUnsupportedClassification() throws Exception {
        mockMvc.perform(get("/reworks/dictionaries")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reason_categories[0].code").value("FIT_ISSUE"))
                .andExpect(jsonPath("$.data.reason_categories[0].label").value("适配问题"))
                .andExpect(jsonPath("$.data.responsibility_types[0].code").value("WORKER"))
                .andExpect(jsonPath("$.data.responsibility_types[0].label").value("生产"));

        submitCheck(nodeInstanceId, 1, true, null);
        startNode(nodeInstanceId);
        completeNode(nodeInstanceId);
        long reworkId = submitCheck(nodeInstanceId, 2, false, nodeInstanceId).path("rework_id").asLong();
        startNode(nodeInstanceId);
        completeNode(nodeInstanceId);
        submitCheck(nodeInstanceId, 2, true, null);

        mockMvc.perform(post("/reworks/{reworkId}/close", reworkId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason_category":"UNLISTED_REASON",
                                  "responsibility_type":"WORKER",
                                  "close_note":"非法字典值"
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/reworks/{reworkId}/close", reworkId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason_category":"FIT_ISSUE",
                                  "responsibility_type":"UNLISTED_OWNER",
                                  "close_note":"非法字典值"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminCanManageReworkDictionaryItemsAndCloseOnlyUsesActiveItems() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String reasonCode = "CUSTOM_REASON_" + suffix;

        mockMvc.perform(post("/reworks/dictionaries/items")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", doctorUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dictionary_type":"REASON_CATEGORY",
                                  "code":"%s",
                                  "label":"客户确认返工",
                                  "sort_order":90
                                }
                                """.formatted(reasonCode)))
                .andExpect(status().isForbidden());

        MvcResult createResult = mockMvc.perform(post("/reworks/dictionaries/items")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dictionary_type":"REASON_CATEGORY",
                                  "code":"%s",
                                  "label":"客户确认返工",
                                  "sort_order":90
                                }
                                """.formatted(reasonCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dictionary_type").value("REASON_CATEGORY"))
                .andExpect(jsonPath("$.data.code").value(reasonCode))
                .andExpect(jsonPath("$.data.label").value("客户确认返工"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn();
        long itemId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data")
                .path("item_id")
                .asLong();

        mockMvc.perform(get("/reworks/dictionaries/items")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .param("dictionary_type", "REASON_CATEGORY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.item_id==%d && @.code=='%s')]".formatted(itemId, reasonCode))
                        .isNotEmpty());

        mockMvc.perform(get("/reworks/dictionaries")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reason_categories[?(@.code=='%s')].label".formatted(reasonCode))
                        .value("客户确认返工"));

        submitCheck(nodeInstanceId, 1, true, null);
        startNode(nodeInstanceId);
        completeNode(nodeInstanceId);
        long reworkId = submitCheck(nodeInstanceId, 2, false, nodeInstanceId).path("rework_id").asLong();
        startNode(nodeInstanceId);
        completeNode(nodeInstanceId);
        submitCheck(nodeInstanceId, 2, true, null);

        mockMvc.perform(post("/reworks/{reworkId}/close", reworkId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason_category":"%s",
                                  "responsibility_type":"WORKER",
                                  "close_note":"使用后台字典关闭返工"
                                }
                                """.formatted(reasonCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reason_category").value(reasonCode));

        mockMvc.perform(put("/reworks/dictionaries/items/{itemId}", itemId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "label":"客户确认返工-停用",
                                  "status":"INACTIVE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));

        mockMvc.perform(get("/reworks/dictionaries")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reason_categories[?(@.code=='%s')]".formatted(reasonCode)).isEmpty());

        long secondReworkId = submitCheck(nodeInstanceId, 2, false, nodeInstanceId).path("rework_id").asLong();
        startNode(nodeInstanceId);
        completeNode(nodeInstanceId);
        submitCheck(nodeInstanceId, 2, true, null);

        mockMvc.perform(post("/reworks/{reworkId}/close", secondReworkId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason_category":"%s",
                                  "responsibility_type":"WORKER",
                                  "close_note":"停用字典不得继续使用"
                                }
                                """.formatted(reasonCode)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reworkLifecycleEmitsInternalNotificationsWithoutDoctorRecipient() throws Exception {
        submitCheck(nodeInstanceId, 1, true, null);
        startNode(nodeInstanceId);
        completeNode(nodeInstanceId);
        long reworkId = submitCheck(nodeInstanceId, 2, false, nodeInstanceId).path("rework_id").asLong();

        assertThat(notificationCount("REWORK_CREATED", "WORKER")).isEqualTo(1L);
        assertThat(userNotificationCount(workerUserId, "REWORK_CREATED")).isEqualTo(1L);
        assertThat(userNotificationCount(doctorUserId, "REWORK_CREATED")).isZero();
        JsonNode createdPayload = latestNotificationPayload("REWORK_CREATED", "WORKER");
        assertThat(createdPayload.path("reworkId").asLong()).isEqualTo(reworkId);
        assertThat(createdPayload.path("targetNodeInstanceId").asLong()).isEqualTo(nodeInstanceId);
        assertThat(createdPayload.path("message").asText()).isEqualTo("返工待处理");

        startNode(nodeInstanceId);
        completeNode(nodeInstanceId);
        submitCheck(nodeInstanceId, 2, true, null);

        mockMvc.perform(post("/reworks/{reworkId}/close", reworkId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason_category":"FIT_ISSUE",
                                  "responsibility_type":"WORKER",
                                  "close_note":"返工复检通过"
                                }
                                """))
                .andExpect(status().isOk());

        assertThat(notificationCount("REWORK_CLOSED", "CS")).isEqualTo(1L);
        assertThat(userNotificationCount(csUserId, "REWORK_CLOSED")).isEqualTo(1L);
        assertThat(userNotificationCount(doctorUserId, "REWORK_CLOSED")).isZero();
        JsonNode closedPayload = latestNotificationPayload("REWORK_CLOSED", "CS");
        assertThat(closedPayload.path("reworkId").asLong()).isEqualTo(reworkId);
        assertThat(closedPayload.path("targetNodeInstanceId").asLong()).isEqualTo(nodeInstanceId);
        assertThat(closedPayload.path("message").asText()).isEqualTo("返工已关闭");
    }

    @Test
    void failedOutCheckResetsTargetAndCompletedDownstreamNodesForReworkImpact() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long clinicId = createClinic("返工影响测试诊所-" + suffix);
        long impactOrderId = createOrder("RW" + suffix.substring(0, 12), clinicId);
        long impactChainId = createTwoNodeChain(suffix);
        List<Long> nodes = instantiateAndAssignAll(impactOrderId, impactChainId);
        long firstNodeId = nodes.get(0);
        long secondNodeId = nodes.get(1);

        submitCheck(firstNodeId, 1, true, null);
        startNode(firstNodeId);
        completeNode(firstNodeId);
        submitCheck(firstNodeId, 2, true, null);

        assertThat(nodeStatus(secondNodeId)).isEqualTo("READY");
        submitCheck(secondNodeId, 1, true, null);
        startNode(secondNodeId);
        completeNode(secondNodeId);

        assertThat(nodeStatus(firstNodeId)).isEqualTo("COMPLETED");
        assertThat(nodeStatus(secondNodeId)).isEqualTo("COMPLETED");

        long reworkId = submitCheck(secondNodeId, 2, false, firstNodeId).path("rework_id").asLong();

        assertThat(reworkId).isPositive();
        assertThat(nodeStatus(firstNodeId)).isEqualTo("READY");
        assertThat(nodeStatus(secondNodeId)).isEqualTo("PENDING");

        submitCheck(firstNodeId, 1, true, null);
        startNode(firstNodeId);
        completeNode(firstNodeId);
        submitCheck(firstNodeId, 2, true, null);

        assertThat(nodeStatus(secondNodeId)).isEqualTo("READY");
    }

    @Test
    void reworkListExposesImpactedDownstreamNodesForAudit() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long clinicId = createClinic("返工审计测试诊所-" + suffix);
        long auditOrderId = createOrder("RA" + suffix.substring(0, 12), clinicId);
        long auditChainId = createTwoNodeChain(suffix);
        List<Long> nodes = instantiateAndAssignAll(auditOrderId, auditChainId);
        long firstNodeId = nodes.get(0);
        long secondNodeId = nodes.get(1);

        submitCheck(firstNodeId, 1, true, null);
        startNode(firstNodeId);
        completeNode(firstNodeId);
        submitCheck(firstNodeId, 2, true, null);

        submitCheck(secondNodeId, 1, true, null);
        startNode(secondNodeId);
        completeNode(secondNodeId);

        long reworkId = submitCheck(secondNodeId, 2, false, firstNodeId).path("rework_id").asLong();

        mockMvc.perform(get("/reworks")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .param("status", "PENDING")
                        .param("order_id", String.valueOf(auditOrderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].rework_id").value(reworkId))
                .andExpect(jsonPath("$.data[0].impacted_node_count").value(1))
                .andExpect(jsonPath("$.data[0].impacted_node_instance_ids[0]").value(secondNodeId));
    }

    @Test
    void reworkListCanFilterRecordsThatImpactedDownstreamNodes() throws Exception {
        submitCheck(nodeInstanceId, 1, true, null);
        startNode(nodeInstanceId);
        completeNode(nodeInstanceId);
        long nonImpactedReworkId = submitCheck(nodeInstanceId, 2, false, nodeInstanceId)
                .path("rework_id")
                .asLong();

        String suffix = UUID.randomUUID().toString().replace("-", "");
        long clinicId = createClinic("返工筛选测试诊所-" + suffix);
        long filteredOrderId = createOrder("RF" + suffix.substring(0, 12), clinicId);
        long filteredChainId = createTwoNodeChain(suffix);
        List<Long> nodes = instantiateAndAssignAll(filteredOrderId, filteredChainId);
        long firstNodeId = nodes.get(0);
        long secondNodeId = nodes.get(1);

        submitCheck(firstNodeId, 1, true, null);
        startNode(firstNodeId);
        completeNode(firstNodeId);
        submitCheck(firstNodeId, 2, true, null);

        submitCheck(secondNodeId, 1, true, null);
        startNode(secondNodeId);
        completeNode(secondNodeId);
        long impactedReworkId = submitCheck(secondNodeId, 2, false, firstNodeId)
                .path("rework_id")
                .asLong();

        JsonNode impactedOnly = performReworkListWithImpactFilter(true);
        assertThat(reworkIds(impactedOnly)).contains(impactedReworkId);
        assertThat(reworkIds(impactedOnly)).doesNotContain(nonImpactedReworkId);
        assertThat(impactedOnly.path("data")).allMatch((node) -> node.path("impacted_node_count").asInt() > 0);

        JsonNode withoutImpact = performReworkListWithImpactFilter(false);
        assertThat(reworkIds(withoutImpact)).contains(nonImpactedReworkId);
        assertThat(reworkIds(withoutImpact)).doesNotContain(impactedReworkId);
        assertThat(withoutImpact.path("data")).allMatch((node) -> node.path("impacted_node_count").asInt() == 0);
    }

    @Test
    void performanceSeparatesReworkResponsibilityAttribution() throws Exception {
        submitCheck(nodeInstanceId, 1, true, null);
        startNode(nodeInstanceId);
        completeNode(nodeInstanceId);
        long workerResponsibleReworkId = submitCheck(nodeInstanceId, 2, false, nodeInstanceId)
                .path("rework_id")
                .asLong();
        startNode(nodeInstanceId);
        completeNode(nodeInstanceId);
        submitCheck(nodeInstanceId, 2, true, null);
        closeRework(workerResponsibleReworkId, "WORKER");

        long doctorResponsibleReworkId = submitCheck(nodeInstanceId, 2, false, nodeInstanceId)
                .path("rework_id")
                .asLong();
        startNode(nodeInstanceId);
        completeNode(nodeInstanceId);
        submitCheck(nodeInstanceId, 2, true, null);
        closeRework(doctorResponsibleReworkId, "DOCTOR");

        mockMvc.perform(get("/performance")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rework_count").value(2))
                .andExpect(jsonPath("$.data.responsible_rework_count").value(1))
                .andExpect(jsonPath("$.data.non_worker_responsibility_rework_count").value(1))
                .andExpect(jsonPath("$.data.unclassified_rework_count").value(0));
    }

    @Test
    void productionQualitySummarySplitsInternalAndExternalReworkRates() throws Exception {
        String productType = "QUALITY_SUMMARY_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String orderSuffix = productType.replace("QUALITY_SUMMARY_", "");
        long clinicId = createClinic("质量汇总测试诊所-" + productType);

        long passOrderId = createOrder("QS_PASS_" + orderSuffix, clinicId, productType);
        long internalReworkOrderId = createOrder("QS_IN_" + orderSuffix, clinicId, productType);
        long externalReworkOrderId = createOrder("QS_OUT_" + orderSuffix, clinicId, productType);

        long passNodeId = instantiateAndAssignAll(passOrderId, chainId).get(0);
        submitCheck(passNodeId, 1, true, null);
        startNode(passNodeId);
        completeNode(passNodeId);
        submitCheck(passNodeId, 2, true, null);

        long internalNodeId = instantiateAndAssignAll(internalReworkOrderId, chainId).get(0);
        submitCheck(internalNodeId, 1, true, null);
        startNode(internalNodeId);
        completeNode(internalNodeId);
        long internalReworkId = submitCheck(internalNodeId, 2, false, internalNodeId)
                .path("rework_id")
                .asLong();
        startNode(internalNodeId);
        completeNode(internalNodeId);
        submitCheck(internalNodeId, 2, true, null);
        closeRework(internalReworkId, "WORKER");

        long externalNodeId = instantiateAndAssignAll(externalReworkOrderId, chainId).get(0);
        submitCheck(externalNodeId, 1, true, null);
        startNode(externalNodeId);
        completeNode(externalNodeId);
        long externalReworkId = submitCheck(externalNodeId, 2, false, externalNodeId)
                .path("rework_id")
                .asLong();
        startNode(externalNodeId);
        completeNode(externalNodeId);
        submitCheck(externalNodeId, 2, true, null);
        closeRework(externalReworkId, "DOCTOR");

        mockMvc.perform(get("/production/quality/summary")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .param("product_type", productType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.product_type").value(productType))
                .andExpect(jsonPath("$.data.inspected_order_count").value(3))
                .andExpect(jsonPath("$.data.total_rework_count").value(2))
                .andExpect(jsonPath("$.data.internal_rework_count").value(1))
                .andExpect(jsonPath("$.data.external_rework_count").value(1))
                .andExpect(jsonPath("$.data.total_rework_rate").value(66.7))
                .andExpect(jsonPath("$.data.internal_rework_rate").value(33.3))
                .andExpect(jsonPath("$.data.external_rework_rate").value(33.3))
                .andExpect(jsonPath("$.data.first_pass_rate").value(33.3))
                .andExpect(jsonPath("$.data.final_pass_rate").value(100.0))
                // 本场景没有客服登记的外返，客诉计数真实为 0；退货率口径尚未启用，必须是 null 而不是 0。
                .andExpect(jsonPath("$.data.complaint_count").value(0))
                .andExpect(jsonPath("$.data.complaint_rate").value(0.0))
                .andExpect(jsonPath("$.data.return_rate").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void productionQualitySummaryComputesComplaintRateFromRegisteredExternalReturns() throws Exception {
        String productType = "COMPLAINT_RATE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String orderSuffix = productType.replace("COMPLAINT_RATE_", "");
        long clinicId = createClinic("客诉率测试诊所-" + productType);
        long complaintOrderId = createOrder("CR_" + orderSuffix, clinicId, productType);

        long nodeId = instantiateAndAssignAll(complaintOrderId, chainId).get(0);
        submitCheck(nodeId, 1, true, null);
        startNode(nodeId);
        completeNode(nodeId);
        submitCheck(nodeId, 2, true, null);

        // 登记前：没有外返登记，客诉计数真实为 0。
        mockMvc.perform(get("/production/quality/summary")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .param("product_type", productType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inspected_order_count").value(1))
                .andExpect(jsonPath("$.data.complaint_count").value(0))
                .andExpect(jsonPath("$.data.complaint_rate").value(0.0));

        mockMvc.perform(post("/quality-records/external-returns")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", 8901L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "order_id": %d,
                                  "reason_category": "FIT_ISSUE",
                                  "responsibility_type": "DOCTOR",
                                  "reason_detail": "客户反馈戴不上，退回处理"
                                }
                                """.formatted(complaintOrderId)))
                .andExpect(status().isOk());

        // 登记后客诉率必须随真实数据变化——证明它不是写死的常量。
        mockMvc.perform(get("/production/quality/summary")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .param("product_type", productType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.complaint_count").value(1))
                .andExpect(jsonPath("$.data.complaint_rate").value(100.0))
                .andExpect(jsonPath("$.data.return_rate").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void doctorCannotReadProductionQualitySummary() throws Exception {
        mockMvc.perform(get("/production/quality/summary")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", doctorUserId))
                .andExpect(status().isForbidden());
    }

    @Test
    void productionQualitySummarySupportsInclusiveDateRangeAndRejectsInvertedRange() throws Exception {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        mockMvc.perform(get("/production/quality/summary")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .param("start_date", tomorrow.toString())
                        .param("end_date", tomorrow.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.start_date").value(tomorrow.toString()))
                .andExpect(jsonPath("$.data.end_date").value(tomorrow.toString()))
                .andExpect(jsonPath("$.data.inspected_order_count").value(0))
                .andExpect(jsonPath("$.data.trends.length()").value(0));

        mockMvc.perform(get("/production/quality/summary")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .param("start_date", tomorrow.toString())
                        .param("end_date", LocalDate.now().toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void performanceDetailsListCompletedWorkLogsForResolvedUser() throws Exception {
        submitCheck(nodeInstanceId, 1, true, null);
        startNode(nodeInstanceId);
        long workLogId = startWorkLog(nodeInstanceId);
        makeWorkLogStartedMinutesAgo(workLogId, 7);
        finishWorkLog(workLogId)
                .andExpect(status().isOk());
        setWorkLogEffectiveSeconds(workLogId, 420);

        mockMvc.perform(get("/performance/details")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .param("user_id", String.valueOf(otherWorkerUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].work_log_id").value(workLogId))
                .andExpect(jsonPath("$.data[0].order_id").value(orderId))
                .andExpect(jsonPath("$.data[0].node_instance_id").value(nodeInstanceId))
                .andExpect(jsonPath("$.data[0].node_name").value("执行测试节点"))
                .andExpect(jsonPath("$.data[0].worker_user_id").value(workerUserId))
                .andExpect(jsonPath("$.data[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data[0].effective_duration").value(7))
                .andExpect(jsonPath("$.data[0].standard_duration").value(10))
                .andExpect(jsonPath("$.data[0].on_time").value(true))
                .andExpect(jsonPath("$.data[0].finished_at").isString());
    }

    @Test
    void performancePeriodFilterAppliesToStatsAndDetails() throws Exception {
        submitCheck(nodeInstanceId, 1, true, null);
        startNode(nodeInstanceId);
        long inPeriodWorkLogId = startWorkLog(nodeInstanceId);
        finishWorkLog(inPeriodWorkLogId)
                .andExpect(status().isOk());
        setWorkLogEffectiveSeconds(inPeriodWorkLogId, 600);
        setWorkLogFinishedAt(inPeriodWorkLogId, "2026-07-10 10:00:00.000");

        long outsideOrderId = createOrder("PERIOD_OUT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), clinicId);
        long outsideNodeId = instantiateAndAssignAll(outsideOrderId, chainId).get(0);
        submitCheck(outsideNodeId, 1, true, null);
        startNode(outsideNodeId);
        long outsidePeriodWorkLogId = startWorkLog(outsideNodeId);
        finishWorkLog(outsidePeriodWorkLogId)
                .andExpect(status().isOk());
        setWorkLogEffectiveSeconds(outsidePeriodWorkLogId, 1200);
        setWorkLogFinishedAt(outsidePeriodWorkLogId, "2026-06-20 10:00:00.000");

        mockMvc.perform(get("/performance")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .param("start_date", "2026-07-01")
                        .param("end_date", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completed_count").value(1))
                .andExpect(jsonPath("$.data.effective_duration").value(10));

        mockMvc.perform(get("/performance/details")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .param("start_date", "2026-07-01")
                        .param("end_date", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].work_log_id").value(inPeriodWorkLogId));
    }

    @Test
    void performanceExposesStandardDurationCoverageAndDefaultFormulaScore() throws Exception {
        submitCheck(nodeInstanceId, 1, true, null);
        startNode(nodeInstanceId);
        long coveredWorkLogId = startWorkLog(nodeInstanceId);
        finishWorkLog(coveredWorkLogId)
                .andExpect(status().isOk());
        setWorkLogEffectiveSeconds(coveredWorkLogId, 480);
        completeNode(nodeInstanceId);
        submitCheck(nodeInstanceId, 2, true, null);

        long noStandardOrderId = createOrder(
                "NO_STANDARD_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                clinicId);
        long noStandardNodeId = instantiateAndAssignAll(noStandardOrderId, chainId).get(0);
        setNodeStandardDuration(noStandardNodeId, null);
        submitCheck(noStandardNodeId, 1, true, null);
        startNode(noStandardNodeId);
        long missingStandardWorkLogId = startWorkLog(noStandardNodeId);
        finishWorkLog(missingStandardWorkLogId)
                .andExpect(status().isOk());
        setWorkLogEffectiveSeconds(missingStandardWorkLogId, 1200);
        completeNode(noStandardNodeId);
        submitCheck(noStandardNodeId, 2, true, null);

        mockMvc.perform(get("/performance")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.performance_formula_version").value("PHASE_ONE_DEFAULT_V1"))
                .andExpect(jsonPath("$.data.standard_duration").value(10))
                .andExpect(jsonPath("$.data.standard_covered_count").value(1))
                .andExpect(jsonPath("$.data.standard_missing_count").value(1))
                .andExpect(jsonPath("$.data.standard_coverage_rate").value(50))
                .andExpect(jsonPath("$.data.duration_efficiency").value(36))
                .andExpect(jsonPath("$.data.performance_score").value(64));
    }

    @Test
    void bearerCsCannotReadWorkerPerformance() throws Exception {
        String csToken = tokenService.issue(new BootstrapIdentity(UserRole.CS, 8001L, null));

        mockMvc.perform(get("/performance")
                        .header("Authorization", "Bearer " + csToken)
                        .param("user_id", String.valueOf(workerUserId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void bearerDoctorCannotReadInternalCheckRecords() throws Exception {
        String doctorToken = tokenService.issue(new BootstrapIdentity(UserRole.DOCTOR, doctorUserId, clinicId));

        mockMvc.perform(get("/check-records/{nodeInstanceId}", nodeInstanceId)
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void bearerDoctorCannotReadReworkRecords() throws Exception {
        String doctorToken = tokenService.issue(new BootstrapIdentity(UserRole.DOCTOR, doctorUserId, clinicId));

        mockMvc.perform(get("/reworks")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void finalInspectionReportRequiresFinalOutPassAndIsInternalOnly() throws Exception {
        long attachmentFileId = createCompletedInternalFile("终检附件.txt");
        long pdfFileId = createCompletedInternalPdfFile("终检报告.pdf");
        String workerWithoutFinalInspectionPermission = tokenService.issue(
                new BootstrapIdentity(UserRole.WORKER, workerUserId, null, null, Set.of("check:write"), "SELF"));
        String finalInspectorToken = tokenService.issue(new BootstrapIdentity(
                UserRole.WORKER,
                workerUserId,
                null,
                null,
                // A 批次后终检报告读取统一走权限码判定：内部读取用 workflow:read-internal，
                // 「只能操作分配给本人的节点」用 workflow:operate-assigned。真实 WORKER 角色两者都已授予。
                Set.of("check:write", "check:read-internal", "final-inspection:manage",
                        "workflow:read-internal", "workflow:operate-assigned"),
                "SELF"));

        mockMvc.perform(get("/final-inspection-reports/{orderId}", orderId)
                        .header("Authorization", "Bearer " + finalInspectorToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/final-inspection-reports/{orderId}", orderId)
                        .param("allow_absent", "true")
                        .header("Authorization", "Bearer " + finalInspectorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

        mockMvc.perform(post("/final-inspection-reports")
                        .header("Authorization", "Bearer " + finalInspectorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"order_id":%d,"summary":"终检报告第一增量"}
                                """.formatted(orderId)))
                .andExpect(status().isConflict());

        submitCheck(nodeInstanceId, 1, true, null);
        startNode(nodeInstanceId);
        completeNode(nodeInstanceId);
        long finalCheckId = submitCheck(nodeInstanceId, 2, true, null).path("check_id").asLong();

        mockMvc.perform(post("/final-inspection-reports")
                        .header("Authorization", "Bearer " + workerWithoutFinalInspectionPermission)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"order_id":%d,"summary":"终检报告第一增量","attachment_file_ids":[%d]}
                                """.formatted(orderId, attachmentFileId)))
                .andExpect(status().isForbidden());

        MvcResult created = mockMvc.perform(post("/final-inspection-reports")
                        .header("Authorization", "Bearer " + finalInspectorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"order_id":%d,"summary":"终检报告第一增量","pdf_file_id":%d,"attachment_file_ids":[%d]}
                                """.formatted(orderId, pdfFileId, attachmentFileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.report_id").isNumber())
                .andExpect(jsonPath("$.data.order_id").value(orderId))
                .andExpect(jsonPath("$.data.final_node_instance_id").value(nodeInstanceId))
                .andExpect(jsonPath("$.data.final_check_id").value(finalCheckId))
                .andExpect(jsonPath("$.data.conclusion").value("PASS"))
                .andExpect(jsonPath("$.data.summary").value("终检报告第一增量"))
                .andExpect(jsonPath("$.data.pdf_file_id").value(pdfFileId))
                .andExpect(jsonPath("$.data.signature_status").value("PENDING"))
                .andExpect(jsonPath("$.data.signed_by_user_id").doesNotExist())
                .andExpect(jsonPath("$.data.signed_at").doesNotExist())
                .andExpect(jsonPath("$.data.attachment_file_ids[0]").value(attachmentFileId))
                .andReturn();
        long reportId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data")
                .path("report_id")
                .asLong();

        mockMvc.perform(get("/final-inspection-reports/{orderId}", orderId)
                        .header("X-Bootstrap-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.report_id").value(reportId))
                .andExpect(jsonPath("$.data.report_no").isNotEmpty())
                .andExpect(jsonPath("$.data.pdf_file_id").value(pdfFileId))
                .andExpect(jsonPath("$.data.signature_status").value("PENDING"))
                .andExpect(jsonPath("$.data.attachment_file_ids[0]").value(attachmentFileId));

        String doctorToken = tokenService.issue(new BootstrapIdentity(UserRole.DOCTOR, doctorUserId, clinicId));
        mockMvc.perform(get("/final-inspection-reports/{orderId}", orderId)
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/files/{fileId}/preview-url", pdfFileId)
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/files/{fileId}/preview-url", attachmentFileId)
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isForbidden());
    }

    private long createCompletedInternalPdfFile(String filename) {
        String objectKey = "test/final-inspection/" + UUID.randomUUID() + "/" + filename;
        jdbcClient.sql("""
                        INSERT INTO file_resource
                            (order_id, owner_user_id, source_type, visibility, bucket_name, object_key,
                             original_filename, content_type, file_size, upload_status, status)
                        VALUES
                            (:orderId, :ownerUserId, 'FINAL_INSPECTION', 'INTERNAL', 'ai-order-files',
                             :objectKey, :filename, 'application/pdf', 256, 'COMPLETED', 'ACTIVE')
                        """)
                .param("orderId", orderId)
                .param("ownerUserId", workerUserId)
                .param("objectKey", objectKey)
                .param("filename", filename)
                .update();
        return jdbcClient.sql("SELECT file_id FROM file_resource WHERE object_key = :objectKey")
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
    }

    private long createCompletedInternalFile(String filename) {
        String objectKey = "test/final-inspection/" + UUID.randomUUID() + "/" + filename;
        jdbcClient.sql("""
                        INSERT INTO file_resource
                            (order_id, owner_user_id, source_type, visibility, bucket_name, object_key,
                             original_filename, content_type, file_size, upload_status, status)
                        VALUES
                            (:orderId, :ownerUserId, 'FINAL_INSPECTION', 'INTERNAL', 'ai-order-files',
                             :objectKey, :filename, 'text/plain', 128, 'COMPLETED', 'ACTIVE')
                        """)
                .param("orderId", orderId)
                .param("ownerUserId", workerUserId)
                .param("objectKey", objectKey)
                .param("filename", filename)
                .update();
        return jdbcClient.sql("SELECT file_id FROM file_resource WHERE object_key = :objectKey")
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
    }

    private long createClinic(String clinicName) {
        jdbcClient.sql("INSERT INTO clinic (clinic_name) VALUES (:clinicName)")
                .param("clinicName", clinicName)
                .update();
        return jdbcClient.sql("SELECT clinic_id FROM clinic WHERE clinic_name = :clinicName")
                .param("clinicName", clinicName)
                .query(Long.class)
                .single();
    }

    private void ensureUser(long userId, String username, String roleCode, Long userClinicId) {
        jdbcClient.sql("""
                        INSERT INTO system_user
                            (user_id, username, password_hash, display_name, clinic_id, user_type, status)
                        VALUES
                            (:userId, :username, 'test-only', :username, :clinicId, :roleCode, 'ACTIVE')
                        """)
                .param("userId", userId)
                .param("username", username)
                .param("clinicId", userClinicId)
                .param("roleCode", roleCode)
                .update();
        jdbcClient.sql("""
                        INSERT INTO system_user_role (user_id, role_id)
                        SELECT :userId, role_id
                        FROM system_role
                        WHERE role_code = :roleCode
                        """)
                .param("userId", userId)
                .param("roleCode", roleCode)
                .update();
    }

    private long createOrder(String orderNo, long clinicId) {
        return createOrder(orderNo, clinicId, "EXECUTION_TEST");
    }

    private long createOrder(String orderNo, long clinicId, String productType) {
        jdbcClient.sql("""
                        INSERT INTO orders
                            (order_no, clinic_id, doctor_user_id, cs_user_id,
                             product_type, internal_status, external_status)
                        VALUES
                            (:orderNo, :clinicId, :doctorUserId, :csUserId,
                             :productType,
                             'PENDING_PRODUCTION_REVIEW', 'PENDING_REVIEW')
                        """)
                .param("orderNo", orderNo)
                .param("clinicId", clinicId)
                .param("doctorUserId", doctorUserId)
                .param("csUserId", csUserId)
                .param("productType", productType)
                .update();
        return jdbcClient.sql("SELECT order_id FROM orders WHERE order_no = :orderNo")
                .param("orderNo", orderNo)
                .query(Long.class)
                .single();
    }

    private long createOneNodeChain(String suffix) {
        String chainCode = "execution_test_" + suffix;
        jdbcClient.sql("""
                        INSERT INTO workflow_chain
                            (chain_code, chain_name, product_type, version, intake_branch, status)
                        VALUES
                            (:chainCode, :chainName, 'EXECUTION_TEST', 1, 'BOTH', 1)
                        """)
                .param("chainCode", chainCode)
                .param("chainName", "执行测试链-" + suffix)
                .update();
        long id = jdbcClient.sql("SELECT chain_id FROM workflow_chain WHERE chain_code = :chainCode")
                .param("chainCode", chainCode)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO workflow_node
                            (chain_id, node_code, process_name, step_order, is_optional,
                             standard_duration, node_category, need_in_check, need_out_check)
                        VALUES
                            (:chainId, 'EXEC_NODE', '执行测试节点', 10, 0,
                             10, 'PRODUCTION', 1, 1)
                        """)
                .param("chainId", id)
                .update();
        return id;
    }

    private long createTwoNodeChain(String suffix) {
        String chainCode = "rework_impact_test_" + suffix;
        jdbcClient.sql("""
                        INSERT INTO workflow_chain
                            (chain_code, chain_name, product_type, version, intake_branch, status)
                        VALUES
                            (:chainCode, :chainName, 'EXECUTION_TEST', 1, 'BOTH', 1)
                        """)
                .param("chainCode", chainCode)
                .param("chainName", "返工影响测试链-" + suffix)
                .update();
        long id = jdbcClient.sql("SELECT chain_id FROM workflow_chain WHERE chain_code = :chainCode")
                .param("chainCode", chainCode)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO workflow_node
                            (chain_id, node_code, process_name, step_order, is_optional,
                             standard_duration, node_category, need_in_check, need_out_check)
                        VALUES
                            (:chainId, 'RW_IMPACT_A', '返工影响前道', 10, 0,
                             10, 'PRODUCTION', 1, 1),
                            (:chainId, 'RW_IMPACT_B', '返工影响后道', 20, 0,
                             10, 'PRODUCTION', 1, 1)
                        """)
                .param("chainId", id)
                .update();
        jdbcClient.sql("""
                        INSERT INTO workflow_edge
                            (chain_id, from_node_id, to_node_id, edge_type)
                        SELECT :chainId, from_node.node_id, to_node.node_id, 'SEQUENCE'
                        FROM workflow_node from_node
                        JOIN workflow_node to_node
                          ON to_node.chain_id = from_node.chain_id
                         AND to_node.node_code = 'RW_IMPACT_B'
                        WHERE from_node.chain_id = :chainId
                          AND from_node.node_code = 'RW_IMPACT_A'
                        """)
                .param("chainId", id)
                .update();
        return id;
    }

    private long instantiateAndAssign() throws Exception {
        MvcResult result = mockMvc.perform(post("/orders/{orderId}/production-review", orderId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 8001L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"APPROVE","chain_id":%d,"intake_branch":"SCAN"}
                                """.formatted(chainId)))
                .andExpect(status().isOk())
                .andReturn();
        long instanceId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("instance_id")
                .asLong();
        markDesignGateConfirmed(orderId);
        long nodeId = jdbcClient.sql("""
                        SELECT node_instance_id
                        FROM order_process_node
                        WHERE instance_id = :instanceId
                          AND node_category = 'PRODUCTION'
                        """)
                .param("instanceId", instanceId)
                .query(Long.class)
                .single();
        mockMvc.perform(post("/orders/{orderId}/process-instance/assign", orderId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assignments":[{"node_instance_id":%d,"user_id":%d}]}
                                """.formatted(nodeId, workerUserId)))
                .andExpect(status().isOk());
        return nodeId;
    }

    private List<Long> instantiateAndAssignAll(long targetOrderId, long targetChainId) throws Exception {
        MvcResult result = mockMvc.perform(post("/orders/{orderId}/production-review", targetOrderId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 8001L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"APPROVE","chain_id":%d,"intake_branch":"SCAN"}
                                """.formatted(targetChainId)))
                .andExpect(status().isOk())
                .andReturn();
        long instanceId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("instance_id")
                .asLong();
        markDesignGateConfirmed(targetOrderId);
        List<Long> nodeIds = jdbcClient.sql("""
                        SELECT node_instance_id
                        FROM order_process_node
                        WHERE instance_id = :instanceId
                          AND node_category = 'PRODUCTION'
                        ORDER BY step_order
                        """)
                .param("instanceId", instanceId)
                .query(Long.class)
                .list();
        String assignments = nodeIds.stream()
                .map((nodeId) -> """
                        {"node_instance_id":%d,"user_id":%d}
                        """.formatted(nodeId, workerUserId).trim())
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
        mockMvc.perform(post("/orders/{orderId}/process-instance/assign", targetOrderId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assignments":[%s]}
                                """.formatted(assignments)))
                .andExpect(status().isOk());
        return nodeIds;
    }

    private void markDesignGateConfirmed(long targetOrderId) {
        // This test class verifies check/rework/worklog mechanics. The
        // end-to-end phase-2 design gate is exercised separately.
        jdbcClient.sql("""
                        UPDATE design_task
                        SET task_status = 'DOCTOR_CONFIRMED'
                        WHERE order_id = :orderId
                        """)
                .param("orderId", targetOrderId)
                .update();
        jdbcClient.sql("""
                        UPDATE order_process_node gate_node
                        JOIN design_task task ON task.node_instance_id = gate_node.node_instance_id
                        SET gate_node.node_status = 'COMPLETED',
                            gate_node.started_at = COALESCE(gate_node.started_at, CURRENT_TIMESTAMP(3)),
                            gate_node.completed_at = CURRENT_TIMESTAMP(3)
                        WHERE task.order_id = :orderId
                          AND gate_node.node_category = 'DESIGN_GATE'
                          AND gate_node.node_status IN ('PENDING', 'READY', 'IN_PROGRESS')
                        """)
                .param("orderId", targetOrderId)
                .update();
        jdbcClient.sql("""
                        UPDATE order_process_node target
                        JOIN (
                            SELECT ready_nodes.node_instance_id
                            FROM (
                                SELECT candidate.node_instance_id
                                FROM order_process_node candidate
                                JOIN design_task selected_task
                                  ON selected_task.order_id = :orderId
                                JOIN order_process_node selected_gate
                                  ON selected_gate.node_instance_id = selected_task.node_instance_id
                                WHERE candidate.instance_id = selected_gate.instance_id
                                  AND candidate.node_status = 'PENDING'
                                  AND NOT EXISTS (
                                      SELECT 1
                                      FROM order_process_edge incoming
                                      JOIN order_process_node predecessor
                                        ON predecessor.node_instance_id = incoming.from_node_instance_id
                                      WHERE incoming.instance_id = candidate.instance_id
                                        AND incoming.to_node_instance_id = candidate.node_instance_id
                                        AND predecessor.node_status NOT IN ('COMPLETED', 'SKIPPED')
                                  )
                            ) ready_nodes
                        ) selected ON selected.node_instance_id = target.node_instance_id
                        SET target.node_status = 'READY'
                        """)
                .param("orderId", targetOrderId)
                .update();
    }

    private JsonNode submitCheck(long nodeId, int checkType, boolean isPass, Long reworkToNodeId) throws Exception {
        String reworkPart = reworkToNodeId == null ? "" : ",\"rework_to_node_id\":" + reworkToNodeId;
        MvcResult result = mockMvc.perform(post("/check-records")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"node_instance_id":%d,"check_type":%d,"is_pass":%s,"remark":"测试"%s}
                                """.formatted(nodeId, checkType, isPass, reworkPart)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private JsonNode performReworkListWithImpactFilter(boolean hasImpactedNodes) throws Exception {
        MvcResult result = mockMvc.perform(get("/reworks")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .param("status", "PENDING")
                        .param("has_impacted_nodes", String.valueOf(hasImpactedNodes)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<Long> reworkIds(JsonNode response) {
        return response.path("data")
                .findValues("rework_id")
                .stream()
                .map(JsonNode::asLong)
                .toList();
    }

    private void startNode(long nodeId) throws Exception {
        mockMvc.perform(post("/process-instance/nodes/{nodeInstanceId}/start", nodeId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId))
                .andExpect(status().isOk());
    }

    private void completeNode(long nodeId) throws Exception {
        mockMvc.perform(post("/process-instance/nodes/{nodeInstanceId}/complete", nodeId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId))
                .andExpect(status().isOk());
    }

    private void closeRework(long reworkId, String responsibilityType) throws Exception {
        mockMvc.perform(post("/reworks/{reworkId}/close", reworkId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason_category":"FIT_ISSUE",
                                  "responsibility_type":"%s",
                                  "close_note":"绩效归因测试"
                                }
                                """.formatted(responsibilityType)))
                .andExpect(status().isOk());
    }

    private long startWorkLog(long nodeId) throws Exception {
        MvcResult result = mockMvc.perform(post("/work-logs/start")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"node_instance_id":%d}
                                """.formatted(nodeId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("work_log_id")
                .asLong();
    }

    private void pauseWorkLog(long workLogId) throws Exception {
        mockMvc.perform(post("/work-logs/{workLogId}/pause", workLogId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId))
                .andExpect(status().isOk());
    }

    private void resumeWorkLog(long workLogId) throws Exception {
        mockMvc.perform(post("/work-logs/{workLogId}/resume", workLogId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions finishWorkLog(long workLogId) throws Exception {
        return mockMvc.perform(post("/work-logs/{workLogId}/finish", workLogId)
                .header("X-Bootstrap-Role", "WORKER")
                .header("X-Bootstrap-User-Id", workerUserId));
    }

    private void makeWorkLogStartedMinutesAgo(long workLogId, int minutes) {
        jdbcClient.sql("""
                        UPDATE work_log
                        SET started_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL :minutes MINUTE)
                        WHERE work_log_id = :workLogId
                        """)
                .param("minutes", minutes)
                .param("workLogId", workLogId)
                .update();
    }

    private void makeOpenPauseStartedMinutesAgo(long workLogId, int minutes) {
        jdbcClient.sql("""
                        UPDATE work_log_pause_segment
                        SET paused_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL :minutes MINUTE)
                        WHERE work_log_id = :workLogId
                          AND resumed_at IS NULL
                        """)
                .param("minutes", minutes)
                .param("workLogId", workLogId)
                .update();
    }

    private void setWorkLogEffectiveSeconds(long workLogId, int effectiveSeconds) {
        jdbcClient.sql("""
                        UPDATE work_log
                        SET effective_duration_seconds = :effectiveSeconds
                        WHERE work_log_id = :workLogId
                        """)
                .param("effectiveSeconds", effectiveSeconds)
                .param("workLogId", workLogId)
                .update();
    }

    private void setWorkLogFinishedAt(long workLogId, String finishedAt) {
        jdbcClient.sql("""
                        UPDATE work_log
                        SET finished_at = :finishedAt
                        WHERE work_log_id = :workLogId
                        """)
                .param("finishedAt", finishedAt)
                .param("workLogId", workLogId)
                .update();
    }

    private void setNodeStandardDuration(long nodeId, Integer standardDuration) {
        jdbcClient.sql("""
                        UPDATE order_process_node
                        SET standard_duration = :standardDuration
                        WHERE node_instance_id = :nodeId
                        """)
                .param("standardDuration", standardDuration)
                .param("nodeId", nodeId)
                .update();
    }

    private long reworkCount(long targetOrderId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM rework_record WHERE order_id = :orderId")
                .param("orderId", targetOrderId)
                .query(Long.class)
                .single();
    }

    private String nodeStatus(long nodeId) {
        return jdbcClient.sql("SELECT node_status FROM order_process_node WHERE node_instance_id = :nodeId")
                .param("nodeId", nodeId)
                .query(String.class)
                .single();
    }

    private String workLogStatus(long workLogId) {
        return jdbcClient.sql("SELECT status FROM work_log WHERE work_log_id = :workLogId")
                .param("workLogId", workLogId)
                .query(String.class)
                .single();
    }

    private long workLogCount(long nodeId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM work_log WHERE node_instance_id = :nodeId")
                .param("nodeId", nodeId)
                .query(Long.class)
                .single();
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

    private JsonNode latestNotificationPayload(String eventType, String audienceRole) throws Exception {
        String payload = jdbcClient.sql("""
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
        return objectMapper.readTree(payload);
    }

    private long userNotificationCount(long userId, String eventType) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM user_notification un
                        JOIN notification_event ne ON ne.event_id = un.event_id
                        WHERE ne.order_id = :orderId
                          AND ne.event_type = :eventType
                          AND un.user_id = :userId
                        """)
                .param("orderId", orderId)
                .param("eventType", eventType)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }
}
