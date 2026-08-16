package com.yuri.aiorder.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.BearerTokenService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * TASK-034 C 批次：管理端角色 / 权限 / 组织管理的验收测试。
 *
 * <p>重点覆盖客户确认的授权边界与「可实操、有留痕、密码不可查看」三条硬要求。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RbacAdminTests {

    private static final long ADMIN_USER_ID = 8001L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private BearerTokenService tokenService;

    @Test
    void adminCanCreateEditAndDisableRoleWithFullAudit() throws Exception {
        // 服务端会把 role_code 规范成大写，测试直接用大写以免误判。
        String roleCode = "TEST_ROLE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();

        String created = mockMvc.perform(post("/rbac/roles")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", ADMIN_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role_code":"%s","role_name":"测试角色","data_scope":"SELF",
                                 "role_level":30,"reason":"C 批次验收"}
                                """.formatted(roleCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role_code").value(roleCode))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        long roleId = Long.parseLong(created.replaceAll(".*\"role_id\":(\\d+).*", "$1"));

        mockMvc.perform(put("/rbac/roles/{roleId}", roleId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", ADMIN_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role_name\":\"测试角色（改名）\",\"data_scope\":\"CLINIC\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role_name").value("测试角色（改名）"))
                .andExpect(jsonPath("$.data.data_scope").value("CLINIC"));

        mockMvc.perform(put("/rbac/roles/{roleId}/status", roleId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", ADMIN_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\",\"reason\":\"停用测试\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));

        // 三次高风险操作都要留痕，且记录修改前后内容。
        List<String> actions = jdbcClient.sql("""
                        SELECT action_type FROM system_rbac_audit
                        WHERE entity_type = 'ROLE' AND entity_label = :roleCode
                        ORDER BY audit_id
                        """)
                .param("roleCode", roleCode)
                .query(String.class)
                .list();
        assertThat(actions).containsExactly("CREATE", "UPDATE", "STATUS_CHANGE");

        String updateBefore = jdbcClient.sql("""
                        SELECT CAST(before_value AS CHAR) FROM system_rbac_audit
                        WHERE entity_type = 'ROLE' AND entity_label = :roleCode AND action_type = 'UPDATE'
                        """)
                .param("roleCode", roleCode)
                .query(String.class)
                .single();
        assertThat(updateBefore).contains("测试角色").doesNotContain("改名");
    }

    @Test
    void portalRoleCannotBeDisabled() throws Exception {
        long csRoleId = roleIdOf("CS");
        mockMvc.perform(put("/rbac/roles/{roleId}/status", csRoleId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", ADMIN_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void managerCannotGrantAdminOrManagerLevelRole() throws Exception {
        long managerUserId = createUser("cs-manager", 110L, List.of("CS", "CS_MANAGER"));
        long targetUserId = createUser("cs-agent", 110L, List.of("CS"));
        String token = tokenFor(managerUserId, UserRole.CS);

        // 经理可以授予等级更低的普通角色。
        mockMvc.perform(put("/rbac/users/{userId}/assignment", targetUserId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role_codes\":[\"CS_AGENT\"],\"reason\":\"分配普通客服\"}"))
                .andExpect(status().isOk())
                // 入口角色 CS 会被保留（按 role_level 排序排在前面），细分角色追加在后。
                .andExpect(jsonPath("$.data.role_codes", org.hamcrest.Matchers.hasItem("CS_AGENT")))
                .andExpect(jsonPath("$.data.role_codes", org.hamcrest.Matchers.hasItem("CS")));

        // 但不能授予经理级。
        mockMvc.perform(put("/rbac/users/{userId}/assignment", targetUserId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role_codes\":[\"CS_MANAGER\"],\"reason\":\"越权尝试\"}"))
                .andExpect(status().isForbidden());

        // 更不能授予管理员级。
        mockMvc.perform(put("/rbac/users/{userId}/assignment", targetUserId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role_codes\":[\"ADMIN\"],\"reason\":\"越权尝试\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void supervisorCannotAssignAcrossDepartments() throws Exception {
        long managerUserId = createUser("prod-manager", 120L, List.of("WORKER", "PROD_MANAGER"));
        long sameDeptUser = createUser("prod-worker-a", 120L, List.of("WORKER"));
        long otherDeptUser = createUser("cs-worker-b", 110L, List.of("CS"));
        String token = tokenFor(managerUserId, UserRole.WORKER);

        mockMvc.perform(put("/rbac/users/{userId}/assignment", sameDeptUser)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role_codes\":[\"PROD_TECHNICIAN\"],\"reason\":\"本部门分配\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/rbac/users/{userId}/assignment", otherDeptUser)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role_codes\":[\"CS_AGENT\"],\"reason\":\"跨部门尝试\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotGrantPermissionCodeTheyDoNotHold() throws Exception {
        long managerUserId = createUser("cs-manager-2", 110L, List.of("CS", "CS_MANAGER"));
        String token = tokenFor(managerUserId, UserRole.CS);
        long agentRoleId = roleIdOf("CS_AGENT");

        // 客服经理没有 account:create，就不能把它授给别的角色。
        mockMvc.perform(put("/rbac/roles/{roleId}/permissions", agentRoleId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permission_codes\":[\"account:create\"],\"reason\":\"越权尝试\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void accountSecurityPermissionsAreSeparateFromBusinessPermissions() throws Exception {
        long managerUserId = createUser("cs-manager-3", 110L, List.of("CS", "CS_MANAGER"));
        long targetUserId = createUser("cs-agent-3", 110L, List.of("CS"));
        String token = tokenFor(managerUserId, UserRole.CS);

        // 部门经理能分配角色（业务数据权限）……
        mockMvc.perform(put("/rbac/users/{userId}/assignment", targetUserId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role_codes\":[\"CS_AGENT\"]}"))
                .andExpect(status().isOk());

        // ……但停用账号与重置密码属账号安全权限，只有管理者账号才有。
        mockMvc.perform(put("/rbac/users/{userId}/status", targetUserId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/rbac/users/{userId}/password-reset", targetUserId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"越权尝试\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void passwordCanOnlyBeResetNeverRead() throws Exception {
        long targetUserId = createUser("reset-target", 110L, List.of("CS"));
        String beforeHash = passwordHashOf(targetUserId);

        String response = mockMvc.perform(post("/rbac/users/{userId}/password-reset", targetUserId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", ADMIN_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"忘记密码\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.temporary_password").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(passwordHashOf(targetUserId)).isNotEqualTo(beforeHash);
        // 响应里不得出现散列值；审计里不得出现口令。
        assertThat(response).doesNotContain(beforeHash);
        String auditAfter = jdbcClient.sql("""
                        SELECT COALESCE(CAST(after_value AS CHAR), '') FROM system_rbac_audit
                        WHERE entity_type = 'ACCOUNT' AND entity_id = :userId AND action_type = 'PASSWORD_RESET'
                        """)
                .param("userId", targetUserId)
                .query(String.class)
                .single();
        assertThat(auditAfter).isEmpty();

        // 用户列表接口任何时候都不返回口令字段。
        mockMvc.perform(get("/rbac/users")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", ADMIN_USER_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("password"))));
    }

    @Test
    void departmentHierarchyAndPostsAreEditable() throws Exception {
        String deptCode = "DEPT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String created = mockMvc.perform(post("/rbac/departments")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", ADMIN_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parent_id":120,"dept_code":"%s","dept_name":"CAD 设计组","sort_order":10}
                                """.formatted(deptCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parent_id").value(120))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        long deptId = Long.parseLong(created.replaceAll(".*\"dept_id\":(\\d+).*", "$1"));

        mockMvc.perform(put("/rbac/departments/{deptId}", deptId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", ADMIN_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parent_id\":120,\"dept_name\":\"CAD 设计一组\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dept_name").value("CAD 设计一组"));

        mockMvc.perform(post("/rbac/posts")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", ADMIN_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"post_code\":\"CAD_DESIGN\",\"post_name\":\"CAD 设计岗\",\"sort_order\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.post_code").value("CAD_DESIGN"));
    }

    @Test
    void permissionMatrixCoversEveryActiveRole() throws Exception {
        long activeRoles = jdbcClient.sql("SELECT COUNT(*) FROM system_role WHERE status = 'ACTIVE'")
                .query(Long.class)
                .single();

        mockMvc.perform(get("/rbac/matrix")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", ADMIN_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles.length()").value((int) activeRoles))
                .andExpect(jsonPath("$.data.permissions_by_role.ADMIN").isArray())
                .andExpect(jsonPath("$.data.menus_by_role.ADMIN").isArray());
    }

    @Test
    void auditEndpointReturnsRecentHighRiskOperations() throws Exception {
        // 这条走 HTTP：之前只用 JDBC 直查审计表，漏掉了 /rbac/audits 自身的 SQL 拼接缺陷。
        String roleCode = "AUDIT_ROLE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        mockMvc.perform(post("/rbac/roles")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", ADMIN_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role_code":"%s","role_name":"审计角色","role_level":30,"reason":"审计接口验收"}
                                """.formatted(roleCode)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/rbac/audits?entity_type=ROLE&limit=10")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", ADMIN_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].entity_type").value("ROLE"))
                .andExpect(jsonPath("$.data[0].operator_username").exists());

        mockMvc.perform(get("/rbac/audits")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", ADMIN_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void workerWithoutRbacPermissionIsDenied() throws Exception {
        mockMvc.perform(get("/rbac/roles")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", 9601L))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/rbac/matrix")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", 9601L))
                .andExpect(status().isForbidden());
    }

    private long roleIdOf(String roleCode) {
        return jdbcClient.sql("SELECT role_id FROM system_role WHERE role_code = :roleCode")
                .param("roleCode", roleCode)
                .query(Long.class)
                .single();
    }

    private String passwordHashOf(long userId) {
        return jdbcClient.sql("SELECT password_hash FROM system_user WHERE user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .single();
    }

    private long createUser(String prefix, Long deptId, List<String> roleCodes) {
        String username = prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        // system_user.user_id 不是自增列，与 dept/menu/post 一样按手工分配。
        long userId = jdbcClient.sql("SELECT COALESCE(MAX(user_id), 0) + 1 FROM system_user")
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO system_user
                            (user_id, username, password_hash, display_name, dept_id, user_type, status)
                        VALUES (:userId, :username, 'x', :displayName, :deptId, 'STAFF', 'ACTIVE')
                        """)
                .param("userId", userId)
                .param("username", username)
                .param("displayName", prefix)
                .param("deptId", deptId)
                .update();
        for (String roleCode : roleCodes) {
            jdbcClient.sql("""
                            INSERT IGNORE INTO system_user_role (user_id, role_id)
                            SELECT :userId, role_id FROM system_role WHERE role_code = :roleCode
                            """)
                    .param("userId", userId)
                    .param("roleCode", roleCode)
                    .update();
        }
        return userId;
    }

    private String tokenFor(long userId, UserRole portalRole) {
        List<String> permissions = jdbcClient.sql("""
                        SELECT DISTINCT p.permission_code
                        FROM system_user_role ur
                        JOIN system_role r ON r.role_id = ur.role_id AND r.status = 'ACTIVE'
                        JOIN system_role_permission rp ON rp.role_id = r.role_id
                        JOIN system_permission p ON p.permission_id = rp.permission_id AND p.status = 'ACTIVE'
                        WHERE ur.user_id = :userId
                        """)
                .param("userId", userId)
                .query(String.class)
                .list();
        return tokenService.issue(new BootstrapIdentity(
                portalRole, userId, null, "test-user", Set.copyOf(permissions), "ALL"));
    }
}
