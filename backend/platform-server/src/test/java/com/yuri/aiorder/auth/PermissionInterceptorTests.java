package com.yuri.aiorder.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "app.auth.allow-role-fallback=false")
@AutoConfigureMockMvc
@Transactional
class PermissionInterceptorTests {

    private static final long DOCTOR_USER_ID = 9701L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    private long clinicId;
    private long orderId;
    private long nodeInstanceId;
    private long fileId;

    @BeforeEach
    void setUp() {
        clinicId = jdbcClient.sql("SELECT clinic_id FROM system_user WHERE username = 'doctor'")
                .query(Long.class)
                .single();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbcClient.sql("""
                        INSERT INTO orders
                            (order_no, clinic_id, doctor_user_id, product_type,
                             form_data, internal_status, external_status, production_note)
                        VALUES
                            (:orderNo, :clinicId, :doctorUserId, 'REGULAR_CROWN',
                             JSON_OBJECT('patient_name', '权限测试', 'tooth_position', '11'),
                             'IN_PRODUCTION', 'PRODUCING', '权限测试内部备注')
                        """)
                .param("orderNo", "PI" + suffix.substring(0, 12))
                .param("clinicId", clinicId)
                .param("doctorUserId", DOCTOR_USER_ID)
                .update();
        orderId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jdbcClient.sql("""
                        INSERT INTO file_resource
                            (order_id, owner_user_id, source_type, visibility, bucket_name, object_key,
                             original_filename, content_type, file_size, upload_status, status)
                        VALUES
                            (:orderId, 8002, 'CHECK', 'INTERNAL', 'ai-order-local', :objectKey,
                             'internal-check.pdf', 'application/pdf', 16, 'COMPLETED', 'ACTIVE')
                        """)
                .param("orderId", orderId)
                .param("objectKey", "tests/datascope/" + suffix + "/internal-check.pdf")
                .update();
        fileId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jdbcClient.sql("""
                        INSERT INTO order_message
                            (order_id, sender_user_id, sender_role, content, visibility, review_status)
                        VALUES
                            (:orderId, 8002, 'CS', 'DataScope可见消息', 'ALL', 'DIRECT')
                        """)
                .param("orderId", orderId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO order_external_projection
                            (order_id, external_status, public_message)
                        VALUES
                            (:orderId, 'PRODUCING', '订单正在制作中。')
                        """)
                .param("orderId", orderId)
                .update();
        createProcessInstance();
    }

    @Test
    void databaseDoctorPermissionCanReadDoctorOrderButCannotUseCsAiEndpoint() throws Exception {
        String doctorToken = login("doctor", "change-me-doctor");

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", hasItem("order:write-doctor")));

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").value(orderId))
                .andExpect(jsonPath("$.data.internal_status").doesNotExist())
                .andExpect(content().string(not(containsString("权限测试内部备注"))));

        mockMvc.perform(post("/ai/cs-query")
                        .header("Authorization", "Bearer " + doctorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"order_id":%d,"question":"内部状态？"}
                                """.formatted(orderId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void doctorReadPermissionDoesNotGrantCaseGroupWrite() throws Exception {
        jdbcClient.sql("""
                        DELETE role_permission
                        FROM system_role_permission role_permission
                        JOIN system_role role ON role.role_id = role_permission.role_id
                        JOIN system_permission permission ON permission.permission_id = role_permission.permission_id
                        WHERE role.role_code = 'DOCTOR'
                          AND permission.permission_code = 'order:write-doctor'
                        """)
                .update();
        String doctorToken = login("doctor", "change-me-doctor");

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", hasItem("order:read-doctor")))
                .andExpect(jsonPath("$.permissions", not(hasItem("order:write-doctor"))));

        mockMvc.perform(post("/order-case-groups")
                        .header("Authorization", "Bearer " + doctorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void databaseWorkerPermissionCannotAssignProcessOrReadPerformanceForOthers() throws Exception {
        String workerToken = login("worker", "change-me-worker");

        mockMvc.perform(post("/orders/{orderId}/process-instance/assign", orderId)
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignments\":[]}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/performance")
                        .header("Authorization", "Bearer " + workerToken)
                        .param("user_id", "8001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user_id").value(9601));
    }

    @Test
    void databaseWorkerSelfDataScopeFiltersOrderAndProcessInstanceQueries() throws Exception {
        String workerToken = login("worker", "change-me-worker");

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/orders/{orderId}/process-instance", orderId)
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/orders/{orderId}/messages", orderId)
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/files/{fileId}/preview-url", fileId)
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());

        jdbcClient.sql("""
                        UPDATE order_process_node
                        SET assigned_user_id = 9601
                        WHERE node_instance_id = :nodeInstanceId
                        """)
                .param("nodeInstanceId", nodeInstanceId)
                .update();

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").value(orderId))
                .andExpect(jsonPath("$.data.internal_status").value("IN_PRODUCTION"));

        mockMvc.perform(get("/orders/{orderId}/process-instance", orderId)
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").value(orderId))
                .andExpect(jsonPath("$.data.nodes[0].node_instance_id").value(nodeInstanceId));

        mockMvc.perform(get("/orders/{orderId}/messages", orderId)
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].order_id").value(orderId))
                .andExpect(jsonPath("$.data[0].content").value("DataScope可见消息"));

        mockMvc.perform(get("/files/{fileId}/preview-url", fileId)
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.file_id").value(fileId));
    }

    @Test
    void databaseCsPermissionCannotReadPerformance() throws Exception {
        String csToken = login("cs", "change-me-cs");

        mockMvc.perform(get("/performance")
                        .header("Authorization", "Bearer " + csToken)
                        .param("user_id", "9601"))
                .andExpect(status().isForbidden());
    }

    private String login(String username, String password) throws Exception {
        String portal = switch (username) {
            case "doctor" -> "DOCTOR";
            case "cs" -> "CS";
            case "worker" -> "PRODUCTION";
            default -> "ADMIN";
        };
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","portal":"%s"}
                                """.formatted(username, password, portal)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("accessToken").asText();
    }

    private void createProcessInstance() {
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
                             is_optional, node_category, need_in_check, need_out_check, node_status)
                        VALUES
                            (:instanceId, :sourceNodeId, :nodeCode, 'DataScope节点', 1,
                             0, 'PRODUCTION', 1, 1, 'READY')
                        """)
                .param("instanceId", instanceId)
                .param("sourceNodeId", sourceNodeId)
                .param("nodeCode", "datascope-" + orderId)
                .update();
        nodeInstanceId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }
}
