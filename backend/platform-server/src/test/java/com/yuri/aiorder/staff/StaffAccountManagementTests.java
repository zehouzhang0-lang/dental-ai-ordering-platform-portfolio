package com.yuri.aiorder.staff;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class StaffAccountManagementTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void adminCreatesWorkerWithDepartmentPostAndLogin() throws Exception {
        String username = "worker-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String password = "PhaseOne!2026";
        String body = """
                {"username":"%s","initial_password":"%s","display_name":"一期新增技工","dept_id":120,"post_id":1003,
                 "permission_codes":["design-draft:internal-review"]}
                """.formatted(username, password);

        mockMvc.perform(post("/staff/accounts")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user_id").isString())
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.dept_name").value("生产中心"))
                .andExpect(jsonPath("$.data.post_name").value("生产员工"))
                .andExpect(jsonPath("$.data.role").value("WORKER"))
                .andExpect(jsonPath("$.data.permission_codes", hasItem("design-draft:internal-review")));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\",\"portal\":\"PRODUCTION\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").isString())
                .andExpect(jsonPath("$.roles", hasItem("WORKER")))
                .andExpect(jsonPath("$.permissions", hasItem("design-draft:internal-review")));

        mockMvc.perform(get("/staff/workload")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .param("keyword", username))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].user_id").isString())
                .andExpect(jsonPath("$.data.items[0].permission_codes", hasItem("design-draft:internal-review")));
    }

    @Test
    void nonAdminCannotCreateWorkerAccount() throws Exception {
        mockMvc.perform(post("/staff/accounts")
                        .header("X-Bootstrap-Role", "CS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"forbidden-worker\",\"initial_password\":\"PhaseOne!2026\",\"display_name\":\"禁止创建\",\"dept_id\":120,\"post_id\":1003}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanUpdateWorkerDepartmentPostAndStatus() throws Exception {
        String username = "worker-update-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String response = mockMvc.perform(post("/staff/accounts")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"initial_password\":\"PhaseOne!2026\",\"display_name\":\"待更新技工\",\"dept_id\":120,\"post_id\":1003}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long userId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(response).path("data").path("user_id").asLong();

        mockMvc.perform(put("/staff/accounts/{userId}", userId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"display_name\":\"已更新技工\",\"dept_id\":130,\"post_id\":1001,\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.display_name").value("已更新技工"))
                .andExpect(jsonPath("$.data.dept_name").value("管理中心"))
                .andExpect(jsonPath("$.data.post_name").value("系统管理员"))
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
    }

    @Test
    void staffOptionsExposeOnlyLeaderPermissionAndRejectOtherDirectPermissions() throws Exception {
        mockMvc.perform(get("/staff/account-options")
                        .header("X-Bootstrap-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions[0].code").value("design-draft:internal-review"));

        String username = "worker-permission-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        mockMvc.perform(post("/staff/accounts")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","initial_password":"PhaseOne!2026",
                                 "display_name":"越权权限测试","dept_id":120,"post_id":1003,
                                 "permission_codes":["design-task:manage"]}
                                """.formatted(username)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminMustTransferActiveDesignTasksBeforeDisablingWorker() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String response = mockMvc.perform(post("/staff/accounts")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"worker-active-%s","initial_password":"PhaseOne!2026",
                                 "display_name":"待转派设计人员","dept_id":120,"post_id":1003}
                                """.formatted(suffix)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String userId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(response).path("data").path("user_id").asText();

        jdbcClient.sql("INSERT INTO clinic (clinic_name) VALUES (:name)")
                .param("name", "账号停用设计任务诊所-" + suffix)
                .update();
        long clinicId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jdbcClient.sql("""
                        INSERT INTO orders
                            (order_no, clinic_id, product_type, internal_status, external_status)
                        VALUES
                            (:orderNo, :clinicId, 'DESIGN_FLOW_TEST', 'IN_DESIGN', 'DESIGNING')
                        """)
                .param("orderNo", "STAFF-DESIGN-" + suffix)
                .param("clinicId", clinicId)
                .update();
        long orderId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jdbcClient.sql("""
                        INSERT INTO design_task (order_id, task_status, assigned_user_id, claimed_at)
                        VALUES (:orderId, 'CLAIMED', :userId, CURRENT_TIMESTAMP(3))
                        """)
                .param("orderId", orderId)
                .param("userId", Long.parseLong(userId))
                .update();

        mockMvc.perform(put("/staff/accounts/{userId}", userId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isConflict());

        jdbcClient.sql("""
                        UPDATE design_task
                        SET task_status = 'DOCTOR_CONFIRMED'
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .update();

        mockMvc.perform(put("/staff/accounts/{userId}", userId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
    }
}
