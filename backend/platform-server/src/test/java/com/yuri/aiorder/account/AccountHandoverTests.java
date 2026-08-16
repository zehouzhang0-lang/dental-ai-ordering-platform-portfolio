package com.yuri.aiorder.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * TASK-034 D 批次：账号交接与人员转移。
 *
 * <p>客户原话「把他账号分配给新同事，**并保留之前得服务记录**」——后半句是重点。
 * 因此每条测试都同时断言两件事：**该转的转了**，且**不该动的一个都没动**。
 * 只验证前者的话，一个全库替换 user_id 的实现也能通过。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountHandoverTests {

    private static final long ADMIN_USER_ID = 9901L;
    private static final long CS_SOURCE_ID = 9902L;
    private static final long CS_SUCCESSOR_ID = 9903L;
    private static final long DOCTOR_SOURCE_ID = 9904L;
    private static final long DOCTOR_SUCCESSOR_ID = 9905L;
    private static final long DOCTOR_OTHER_CLINIC_ID = 9906L;
    private static final long WORKER_USER_ID = 9907L;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private long clinicId;
    private long otherClinicId;
    private long patientId;
    private long orderId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        clinicId = createClinic("交接测试诊所-" + suffix);
        otherClinicId = createClinic("另一家诊所-" + suffix);
        upsertUser(ADMIN_USER_ID, "handover-admin-" + suffix, "ADMIN", null);
        upsertUser(CS_SOURCE_ID, "handover-cs-from-" + suffix, "CS", null);
        upsertUser(CS_SUCCESSOR_ID, "handover-cs-to-" + suffix, "CS", null);
        upsertUser(DOCTOR_SOURCE_ID, "handover-doc-from-" + suffix, "DOCTOR", clinicId);
        upsertUser(DOCTOR_SUCCESSOR_ID, "handover-doc-to-" + suffix, "DOCTOR", clinicId);
        upsertUser(DOCTOR_OTHER_CLINIC_ID, "handover-doc-other-" + suffix, "DOCTOR", otherClinicId);
        upsertUser(WORKER_USER_ID, "handover-worker-" + suffix, "WORKER", null);

        patientId = createPatient("交接测试患者", DOCTOR_SOURCE_ID);
        orderId = createOrder(suffix);
    }

    // ---------------------------------------------------------------------
    // 转移当前负责关系
    // ---------------------------------------------------------------------

    @Test
    void handoverMovesCurrentOwnershipToTheSuccessor() throws Exception {
        mockMvc.perform(get("/accounts/{userId}/handover-preview", DOCTOR_SOURCE_ID)
                        .param("successor_user_id", String.valueOf(DOCTOR_SUCCESSOR_ID))
                        .headers(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.portal_role").value("DOCTOR"))
                .andExpect(jsonPath("$.data.total_object_count").value(
                        org.hamcrest.Matchers.greaterThan(0)))
                // 界面上要明确告诉操作人哪些不会跟着走。
                .andExpect(jsonPath("$.data.historical_records_kept.length()").value(
                        org.hamcrest.Matchers.greaterThan(0)));

        mockMvc.perform(post("/accounts/{userId}/handover", DOCTOR_SOURCE_ID)
                        .headers(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"successor_user_id": %d, "reason": "医生离职", "acknowledged": true}
                                """.formatted(DOCTOR_SUCCESSOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.from_user_id").value(DOCTOR_SOURCE_ID))
                .andExpect(jsonPath("$.data.to_user_id").value(DOCTOR_SUCCESSOR_ID))
                .andExpect(jsonPath("$.data.transferred_object_count").value(
                        org.hamcrest.Matchers.greaterThan(0)));

        // 验收「转移后新责任人能看到并处理相应对象」：订单与患者都归了承接人。
        assertThat(orderDoctor(orderId)).isEqualTo(DOCTOR_SUCCESSOR_ID);
        assertThat(patientDoctor(patientId)).isEqualTo(DOCTOR_SUCCESSOR_ID);
        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_SUCCESSOR_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk());
    }

    /**
     * 验收第二条：转移后查询历史记录，原责任人姓名仍出现在历史节点上。
     * 这是这一批最容易做错的地方——无脑替换 user_id 会把绩效与责任一起改写。
     */
    @Test
    void historicalFactsKeepTheOriginalOperatorAfterHandover() throws Exception {
        long instanceId = createProcessInstance(orderId);
        long completedNodeId = createNode(instanceId, "COMPLETED", WORKER_USER_ID);
        long pendingNodeId = createNode(instanceId, "READY", WORKER_USER_ID);
        long workLogId = createWorkLog(completedNodeId, WORKER_USER_ID);
        long checkRecordId = createCheckRecord(completedNodeId, WORKER_USER_ID);
        long historyId = createStatusHistory(orderId, WORKER_USER_ID);

        long successorWorkerId = WORKER_USER_ID + 50;
        upsertUser(successorWorkerId, "handover-worker-to-" + UUID.randomUUID(), "WORKER", null);

        mockMvc.perform(post("/accounts/{userId}/handover", WORKER_USER_ID)
                        .headers(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"successor_user_id": %d, "reason": "技工转岗", "acknowledged": true}
                                """.formatted(successorWorkerId)))
                .andExpect(status().isOk());

        // 未完成的工序转给了承接人——这是「进行中任务」。
        assertThat(nodeAssignee(pendingNodeId)).isEqualTo(successorWorkerId);

        // 已完成的工序、工时、质检、状态流转全部保留原执行人——这些是历史事实。
        assertThat(nodeAssignee(completedNodeId)).isEqualTo(WORKER_USER_ID);
        assertThat(scalar("SELECT worker_user_id FROM work_log WHERE work_log_id = " + workLogId))
                .isEqualTo(WORKER_USER_ID);
        assertThat(scalar("SELECT checker_user_id FROM check_record WHERE check_id = " + checkRecordId))
                .isEqualTo(WORKER_USER_ID);
        assertThat(scalar(
                "SELECT operator_user_id FROM order_status_history WHERE history_id = " + historyId))
                .isEqualTo(WORKER_USER_ID);
    }

    @Test
    void handoverRecordsFullAuditIncludingTheTransferredObjectList() throws Exception {
        String response = mockMvc.perform(post("/accounts/{userId}/handover", DOCTOR_SOURCE_ID)
                        .headers(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"successor_user_id": %d, "reason": "转诊", "acknowledged": true}
                                """.formatted(DOCTOR_SUCCESSOR_ID)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long handoverId = objectMapper.readTree(response).path("data").path("handover_id").asLong();

        // 客户要求的留痕六项：操作人、时间、原责任人、承接人、转移对象清单、原因。
        mockMvc.perform(get("/accounts/handovers/{handoverId}", handoverId).headers(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operator_user_id").value(ADMIN_USER_ID))
                .andExpect(jsonPath("$.data.created_at").exists())
                .andExpect(jsonPath("$.data.from_user_id").value(DOCTOR_SOURCE_ID))
                .andExpect(jsonPath("$.data.to_user_id").value(DOCTOR_SUCCESSOR_ID))
                .andExpect(jsonPath("$.data.reason").value("转诊"))
                // 「转移对象清单」是具体主键，不只是数量——只记「转了 12 条」事后无法追溯。
                .andExpect(jsonPath("$.data.items[?(@.object_type=='ORDER_DOCTOR')].object_ids")
                        .value(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.hasItem((int) orderId))));

        mockMvc.perform(get("/accounts/handovers").headers(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void handoverCanDisableTheSourceAccountAndRevokeItsSessions() throws Exception {
        jdbcClient.sql("""
                        INSERT INTO auth_refresh_token (family_id, user_id, token_hash, expires_at)
                        VALUES (:familyId, :userId, :hash, DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY))
                        """)
                .param("familyId", UUID.randomUUID().toString())
                .param("userId", CS_SOURCE_ID)
                .param("hash", "handover-test-" + UUID.randomUUID())
                .update();

        mockMvc.perform(post("/accounts/{userId}/handover", CS_SOURCE_ID)
                        .headers(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"successor_user_id": %d, "acknowledged": true,
                                 "disable_source_account": true}
                                """.formatted(CS_SUCCESSOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source_disabled").value(true));

        assertThat(scalarString("SELECT status FROM system_user WHERE user_id = " + CS_SOURCE_ID))
                .isEqualTo("DISABLED");
        // 停用后旧会话必须失效，否则「已停用」只是列表上的一个字。
        assertThat(scalar("SELECT COUNT(*) FROM auth_refresh_token WHERE user_id = " + CS_SOURCE_ID))
                .isZero();
    }

    // ---------------------------------------------------------------------
    // 边界与越权
    // ---------------------------------------------------------------------

    @Test
    void successorMustUseTheSamePortalRole() throws Exception {
        // 把医生的病例转给客服，接手的人进不了医生端，数据还会落在他看不见的范围外。
        mockMvc.perform(post("/accounts/{userId}/handover", DOCTOR_SOURCE_ID)
                        .headers(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"successor_user_id": %d, "acknowledged": true}
                                """.formatted(CS_SUCCESSOR_ID)))
                .andExpect(status().isConflict());
        assertThat(orderDoctor(orderId)).isEqualTo(DOCTOR_SOURCE_ID);
    }

    @Test
    void doctorHandoverCannotCrossClinics() throws Exception {
        // 跨诊所转病例等于把患者资料交给了另一家客户。
        mockMvc.perform(post("/accounts/{userId}/handover", DOCTOR_SOURCE_ID)
                        .headers(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"successor_user_id": %d, "acknowledged": true}
                                """.formatted(DOCTOR_OTHER_CLINIC_ID)))
                .andExpect(status().isConflict());
        assertThat(patientDoctor(patientId)).isEqualTo(DOCTOR_SOURCE_ID);
    }

    @Test
    void handoverToDisabledOrSameAccountIsRejected() throws Exception {
        mockMvc.perform(post("/accounts/{userId}/handover", DOCTOR_SOURCE_ID)
                        .headers(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"successor_user_id": %d, "acknowledged": true}
                                """.formatted(DOCTOR_SOURCE_ID)))
                .andExpect(status().isBadRequest());

        jdbcClient.sql("UPDATE system_user SET status = 'DISABLED' WHERE user_id = :userId")
                .param("userId", DOCTOR_SUCCESSOR_ID)
                .update();
        try {
            mockMvc.perform(post("/accounts/{userId}/handover", DOCTOR_SOURCE_ID)
                            .headers(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"successor_user_id": %d, "acknowledged": true}
                                    """.formatted(DOCTOR_SUCCESSOR_ID)))
                    .andExpect(status().isConflict());
        } finally {
            jdbcClient.sql("UPDATE system_user SET status = 'ACTIVE' WHERE user_id = :userId")
                    .param("userId", DOCTOR_SUCCESSOR_ID)
                    .update();
        }
    }

    @Test
    void handoverWithoutExplicitAcknowledgementIsRejected() throws Exception {
        mockMvc.perform(post("/accounts/{userId}/handover", DOCTOR_SOURCE_ID)
                        .headers(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"successor_user_id": %d}
                                """.formatted(DOCTOR_SUCCESSOR_ID)))
                .andExpect(status().isBadRequest());
        assertThat(orderDoctor(orderId)).isEqualTo(DOCTOR_SOURCE_ID);
    }

    @Test
    void unauthorizedAccountsCannotExecuteOrReadHandovers() throws Exception {
        // 客服、生产、医生端都不是账号安全操作的授权对象。
        for (String[] actor : new String[][] {
                {"CS", String.valueOf(CS_SOURCE_ID)},
                {"WORKER", String.valueOf(WORKER_USER_ID)},
                {"DOCTOR", String.valueOf(DOCTOR_SOURCE_ID)}}) {
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-Bootstrap-Role", actor[0]);
            headers.add("X-Bootstrap-User-Id", actor[1]);
            if ("DOCTOR".equals(actor[0])) {
                headers.add("X-Bootstrap-Clinic-Id", String.valueOf(clinicId));
            }
            mockMvc.perform(post("/accounts/{userId}/handover", DOCTOR_SOURCE_ID)
                            .headers(headers)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"successor_user_id": %d, "acknowledged": true}
                                    """.formatted(DOCTOR_SUCCESSOR_ID)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/accounts/handovers").headers(headers))
                    .andExpect(status().isForbidden());
        }
        assertThat(orderDoctor(orderId)).isEqualTo(DOCTOR_SOURCE_ID);
    }

    @Test
    void removingTheHandoverPermissionCodeDeniesAccessEvenForAdminPortal() throws Exception {
        revokePermission("ADMIN", "account:handover");
        try {
            mockMvc.perform(post("/accounts/{userId}/handover", DOCTOR_SOURCE_ID)
                            .headers(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"successor_user_id": %d, "acknowledged": true}
                                    """.formatted(DOCTOR_SUCCESSOR_ID)))
                    .andExpect(status().isForbidden());
        } finally {
            grantPermission("ADMIN", "account:handover");
        }
    }

    @Test
    void disablingTheSourceAccountNeedsTheAccountDisablePermissionOnTopOfHandover() throws Exception {
        revokePermission("ADMIN", "account:disable");
        try {
            // 交接本身可以做，但顺带停用账号要另一个权限码，不因为「顺带」而放宽。
            mockMvc.perform(post("/accounts/{userId}/handover", CS_SOURCE_ID)
                            .headers(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"successor_user_id": %d, "acknowledged": true,
                                     "disable_source_account": true}
                                    """.formatted(CS_SUCCESSOR_ID)))
                    .andExpect(status().isForbidden());
            assertThat(scalarString("SELECT status FROM system_user WHERE user_id = " + CS_SOURCE_ID))
                    .isEqualTo("ACTIVE");
        } finally {
            grantPermission("ADMIN", "account:disable");
        }
    }

    // ---------------------------------------------------------------------
    // 脚手架
    // ---------------------------------------------------------------------

    private HttpHeaders admin() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Bootstrap-Role", "ADMIN");
        headers.add("X-Bootstrap-User-Id", String.valueOf(ADMIN_USER_ID));
        return headers;
    }

    private long orderDoctor(long id) {
        return scalar("SELECT doctor_user_id FROM orders WHERE order_id = " + id);
    }

    private long patientDoctor(long id) {
        return scalar("SELECT doctor_user_id FROM patient_record WHERE patient_id = " + id);
    }

    private long nodeAssignee(long id) {
        return scalar("SELECT assigned_user_id FROM order_process_node WHERE node_instance_id = " + id);
    }

    private long scalar(String sql) {
        Long value = jdbcClient.sql(sql).query(Long.class).optional().orElse(null);
        return value == null ? -1L : value;
    }

    private String scalarString(String sql) {
        return jdbcClient.sql(sql).query(String.class).single();
    }

    private long createClinic(String name) {
        jdbcClient.sql("INSERT INTO clinic (clinic_name) VALUES (:name)")
                .param("name", name)
                .update();
        return jdbcClient.sql("SELECT clinic_id FROM clinic WHERE clinic_name = :name")
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long createPatient(String name, long doctorUserId) {
        jdbcClient.sql("""
                        INSERT INTO patient_record (clinic_id, doctor_user_id, patient_name)
                        VALUES (:clinicId, :doctorUserId, :name)
                        """)
                .param("clinicId", clinicId)
                .param("doctorUserId", doctorUserId)
                .param("name", name)
                .update();
        return lastId();
    }

    private long createOrder(String suffix) {
        jdbcClient.sql("""
                        INSERT INTO orders
                            (order_no, clinic_id, doctor_user_id, patient_id, product_type,
                             form_data, internal_status, external_status)
                        VALUES
                            (:orderNo, :clinicId, :doctorUserId, :patientId, 'REGULAR_CROWN',
                             JSON_OBJECT(), 'IN_PRODUCTION', 'PRODUCING')
                        """)
                .param("orderNo", "HO-" + suffix.substring(0, 16))
                .param("clinicId", clinicId)
                .param("doctorUserId", DOCTOR_SOURCE_ID)
                .param("patientId", patientId)
                .update();
        return lastId();
    }

    private long createProcessInstance(long forOrderId) {
        Long chainId = jdbcClient.sql("SELECT chain_id FROM workflow_chain ORDER BY chain_id LIMIT 1")
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO order_process_instance
                            (order_id, chain_id, chain_version, intake_branch_used, instance_status)
                        VALUES (:orderId, :chainId, 1, 'BOTH', 'RUNNING')
                        """)
                .param("orderId", forOrderId)
                .param("chainId", chainId)
                .update();
        return lastId();
    }

    private int nodeSequence;

    private long createNode(long instanceId, String status, long assignee) {
        nodeSequence++;
        jdbcClient.sql("""
                        INSERT INTO order_process_node
                            (instance_id, source_node_id, node_code, process_name, node_category,
                             step_order, node_status, assigned_user_id)
                        VALUES (:instanceId, :sourceNodeId, :nodeCode, 'CAD设计', 'PRODUCTION',
                                :stepOrder, :status, :assignee)
                        """)
                .param("instanceId", instanceId)
                .param("sourceNodeId", jdbcClient
                        .sql("SELECT node_id FROM workflow_node ORDER BY node_id LIMIT 1")
                        .query(Long.class)
                        .single())
                .param("nodeCode", "CAD_" + nodeSequence)
                .param("stepOrder", nodeSequence)
                .param("status", status)
                .param("assignee", assignee)
                .update();
        return lastId();
    }

    private long createWorkLog(long nodeInstanceId, long workerUserId) {
        jdbcClient.sql("""
                        INSERT INTO work_log
                            (order_id, node_instance_id, worker_user_id, started_at,
                             finished_at, effective_duration_seconds, status)
                        VALUES (:orderId, :nodeInstanceId, :workerUserId,
                                CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 1800, 'FINISHED')
                        """)
                .param("orderId", orderId)
                .param("nodeInstanceId", nodeInstanceId)
                .param("workerUserId", workerUserId)
                .update();
        return lastId();
    }

    private long createCheckRecord(long nodeInstanceId, long checkerUserId) {
        jdbcClient.sql("""
                        INSERT INTO check_record
                            (order_id, node_instance_id, check_type, result, checker_user_id)
                        VALUES (:orderId, :nodeInstanceId, 1, 1, :checkerUserId)
                        """)
                .param("orderId", orderId)
                .param("nodeInstanceId", nodeInstanceId)
                .param("checkerUserId", checkerUserId)
                .update();
        return lastId();
    }

    private long createStatusHistory(long forOrderId, long operatorUserId) {
        jdbcClient.sql("""
                        INSERT INTO order_status_history
                            (order_id, from_internal_status, to_internal_status,
                             from_external_status, to_external_status, event_type, operator_user_id)
                        VALUES (:orderId, 'ASSIGNED', 'IN_PRODUCTION',
                                'PRODUCING', 'PRODUCING', 'HANDOVER_TEST', :operatorUserId)
                        """)
                .param("orderId", forOrderId)
                .param("operatorUserId", operatorUserId)
                .update();
        return lastId();
    }

    private long lastId() {
        return jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    private void revokePermission(String roleCode, String permissionCode) {
        jdbcClient.sql("""
                        DELETE rp FROM system_role_permission rp
                        JOIN system_role r ON r.role_id = rp.role_id
                        JOIN system_permission p ON p.permission_id = rp.permission_id
                        WHERE r.role_code = :roleCode AND p.permission_code = :permissionCode
                        """)
                .param("roleCode", roleCode)
                .param("permissionCode", permissionCode)
                .update();
    }

    private void grantPermission(String roleCode, String permissionCode) {
        jdbcClient.sql("""
                        INSERT IGNORE INTO system_role_permission (role_id, permission_id)
                        SELECT r.role_id, p.permission_id
                        FROM system_role r
                        JOIN system_permission p ON p.permission_code = :permissionCode
                        WHERE r.role_code = :roleCode
                        """)
                .param("roleCode", roleCode)
                .param("permissionCode", permissionCode)
                .update();
    }

    private void upsertUser(long userId, String username, String userType, Long userClinicId) {
        jdbcClient.sql("""
                        INSERT INTO system_user
                            (user_id, username, password_hash, display_name, clinic_id, user_type, status)
                        VALUES
                            (:userId, :username, 'test-only', :username, :clinicId, :userType, 'ACTIVE')
                        ON DUPLICATE KEY UPDATE
                            username = VALUES(username),
                            clinic_id = VALUES(clinic_id),
                            user_type = VALUES(user_type),
                            status = 'ACTIVE'
                        """)
                .param("userId", userId)
                .param("username", username.length() > 60 ? username.substring(0, 60) : username)
                .param("clinicId", userClinicId)
                .param("userType", userType)
                .update();
    }
}
