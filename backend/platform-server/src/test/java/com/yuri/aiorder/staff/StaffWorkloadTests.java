package com.yuri.aiorder.staff;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class StaffWorkloadTests {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    private long workerUserId;
    private String displayName;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        workerUserId = 920_000_000L + Math.abs(suffix.hashCode());
        displayName = "人员工作量测试员工-" + suffix.substring(0, 8);
        seedWorkerWithWorkload(workerUserId, displayName);
    }

    @Test
    void adminCanListStaffProfileAndWorkload() throws Exception {
        mockMvc.perform(get("/staff/workload")
                        .param("keyword", displayName)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 8001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].user_id").value(workerUserId))
                .andExpect(jsonPath("$.data.items[0].display_name").value(displayName))
                .andExpect(jsonPath("$.data.items[0].user_type").value("WORKER"))
                .andExpect(jsonPath("$.data.items[0].dept_name").value("生产中心"))
                .andExpect(jsonPath("$.data.items[0].post_names", hasItem("生产员工")))
                .andExpect(jsonPath("$.data.items[0].role_codes", hasItem("WORKER")))
                .andExpect(jsonPath("$.data.items[0].assigned_node_count").value(2))
                .andExpect(jsonPath("$.data.items[0].active_node_count").value(1))
                .andExpect(jsonPath("$.data.items[0].completed_work_log_count").value(1))
                .andExpect(jsonPath("$.data.items[0].effective_duration").value(45))
                .andExpect(jsonPath("$.data.items[0].rework_count").value(1))
                .andExpect(jsonPath("$.data.items[0].last_work_finished_at").exists())
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.not(containsString("password_hash"))));
    }

    @Test
    void workerCanOnlyReadOwnStaffWorkload() throws Exception {
        mockMvc.perform(get("/staff/workload")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].user_id").value(workerUserId))
                .andExpect(jsonPath("$.data.items[0].display_name").value(displayName));
    }

    @Test
    void doctorCannotReadStaffWorkload() throws Exception {
        mockMvc.perform(get("/staff/workload")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", 9701L)
                        .header("X-Bootstrap-Clinic-Id", 1L))
                .andExpect(status().isForbidden());
    }

    private void seedWorkerWithWorkload(long userId, String displayName) {
        jdbcClient.sql("""
                        INSERT INTO system_user
                            (user_id, username, password_hash, display_name, dept_id, user_type, status)
                        VALUES
                            (:userId, :username, 'pbkdf2_sha256$120000$test$test',
                             :displayName, 120, 'WORKER', 'ACTIVE')
                        """)
                .param("userId", userId)
                .param("username", "staff-workload-" + userId)
                .param("displayName", displayName)
                .update();
        jdbcClient.sql("""
                        INSERT INTO system_user_role (user_id, role_id)
                        SELECT :userId, role_id
                        FROM system_role
                        WHERE role_code = 'WORKER'
                        """)
                .param("userId", userId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO system_user_post (user_id, post_id)
                        SELECT :userId, post_id
                        FROM system_post
                        WHERE post_code = 'PRODUCTION_WORKER'
                        """)
                .param("userId", userId)
                .update();

        long clinicId = jdbcClient.sql("SELECT clinic_id FROM clinic ORDER BY clinic_id LIMIT 1")
                .query(Long.class)
                .single();
        long chainId = jdbcClient.sql("SELECT chain_id FROM workflow_chain ORDER BY chain_id LIMIT 1")
                .query(Long.class)
                .single();
        long sourceNodeId = jdbcClient.sql("SELECT node_id FROM workflow_node WHERE chain_id = :chainId ORDER BY step_order LIMIT 1")
                .param("chainId", chainId)
                .query(Long.class)
                .single();

        jdbcClient.sql("""
                        INSERT INTO orders
                            (order_no, clinic_id, doctor_user_id, product_type, form_data,
                             internal_status, external_status)
                        VALUES
                            (:orderNo, :clinicId, 9701, 'REGULAR_CROWN', CAST('{}' AS JSON),
                             'PROCESS_INSTANCE_CREATED', 'PRODUCING')
                        """)
                .param("orderNo", "STAFF-" + userId)
                .param("clinicId", clinicId)
                .update();
        long orderId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();

        jdbcClient.sql("""
                        INSERT INTO order_process_instance
                            (order_id, chain_id, chain_version, intake_branch_used, instance_status)
                        VALUES (:orderId, :chainId, 1, 'BOTH', 'IN_PROGRESS')
                        """)
                .param("orderId", orderId)
                .param("chainId", chainId)
                .update();
        long instanceId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();

        long completedNodeId = insertNode(instanceId, sourceNodeId, userId, "COMPLETED", "STAFF_DONE_" + userId);
        long activeNodeId = insertNode(instanceId, sourceNodeId, userId, "IN_PROGRESS", "STAFF_ACTIVE_" + userId);

        jdbcClient.sql("""
                        INSERT INTO work_log
                            (order_id, node_instance_id, worker_user_id, started_at, finished_at,
                             effective_duration_seconds, status)
                        VALUES
                            (:orderId, :nodeId, :userId,
                             DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 60 MINUTE),
                             DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 15 MINUTE),
                             2700, 'COMPLETED')
                        """)
                .param("orderId", orderId)
                .param("nodeId", completedNodeId)
                .param("userId", userId)
                .update();

        jdbcClient.sql("""
                        INSERT INTO check_record (order_id, node_instance_id, check_type, result, checker_user_id, note)
                        VALUES (:orderId, :nodeId, 'OUT', 'FAIL', 8001, '人员工作量测试返工')
                        """)
                .param("orderId", orderId)
                .param("nodeId", activeNodeId)
                .update();
        long checkId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jdbcClient.sql("""
                        INSERT INTO rework_record
                            (order_id, source_check_id, from_node_instance_id, target_node_instance_id,
                             responsibility_type, status)
                        VALUES (:orderId, :checkId, :fromNodeId, :targetNodeId, 'WORKER', 'PENDING')
                        """)
                .param("orderId", orderId)
                .param("checkId", checkId)
                .param("fromNodeId", activeNodeId)
                .param("targetNodeId", activeNodeId)
                .update();
    }

    private long insertNode(long instanceId, long sourceNodeId, long userId, String status, String nodeCode) {
        jdbcClient.sql("""
                        INSERT INTO order_process_node
                            (instance_id, source_node_id, node_code, process_name, stage_name, step_order,
                             node_category, node_status, assigned_user_id)
                        VALUES
                            (:instanceId, :sourceNodeId, :nodeCode, '人员工作量测试工序',
                             '测试阶段', 1, 'PRODUCTION', :status, :userId)
                        """)
                .param("instanceId", instanceId)
                .param("sourceNodeId", sourceNodeId)
                .param("nodeCode", nodeCode)
                .param("status", status)
                .param("userId", userId)
                .update();
        return jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }
}
