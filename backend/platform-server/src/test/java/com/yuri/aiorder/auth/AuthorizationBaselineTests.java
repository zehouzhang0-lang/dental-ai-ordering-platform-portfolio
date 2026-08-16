package com.yuri.aiorder.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.AccessControlService;
import com.yuri.aiorder.common.auth.AuthenticatedUser;
import com.yuri.aiorder.common.auth.DatabaseAuthService;
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
 * TASK-034 A 批次：授权底座统一的验收测试。
 *
 * <p>覆盖两条验收要求：
 * <ol>
 *   <li>删掉角色上的某个权限码后，对应接口真的返回 403（此前靠入口角色兜底，删了也访问得到）；</li>
 *   <li>数据范围解析顺序为 用户级覆盖 &gt; 角色级配置 &gt; 入口角色默认值。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthorizationBaselineTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private DatabaseAuthService databaseAuthService;

    @Autowired
    private AccessControlService accessControlService;

    @Test
    void removingPermissionCodeFromRoleDeniesAccessEvenWhenPortalRoleMatches() throws Exception {
        mockMvc.perform(get("/dashboards/phase-one-ab")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", 9601L))
                .andExpect(status().isOk());

        revokeFromRole("WORKER", "dashboard:read-internal");

        // 入口角色仍然是 WORKER，但权限码没了就必须拒绝——这正是新增细分角色能被管住的前提。
        mockMvc.perform(get("/dashboards/phase-one-ab")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", 9601L))
                .andExpect(status().isForbidden());
    }

    @Test
    void roleLevelDataScopeIsAuthoritativeInsteadOfPortalRoleDefault() {
        // 基线：CS 角色配置为 ALL，登录后就是 ALL。
        assertThat(databaseAuthService.loadAuthenticatedUser(8002L).dataScope()).isEqualTo("ALL");

        jdbcClient.sql("UPDATE system_role SET data_scope = 'SELF' WHERE role_code = 'CS'").update();

        // 改成 SELF 后必须立刻生效。旧实现里「入口角色是 CS 就返回 ALL」会把这条配置吃掉，
        // 客户要的「客服经理=全公司 / 普通客服=本人负责」在那种写法下根本配不出来。
        AuthenticatedUser user = databaseAuthService.loadAuthenticatedUser(8002L);
        assertThat(user.dataScope()).isEqualTo("SELF");
    }

    @Test
    void userLevelDataScopeOverridesRoleLevelConfiguration() {
        assertThat(databaseAuthService.loadAuthenticatedUser(9601L).dataScope()).isEqualTo("SELF");

        jdbcClient.sql("UPDATE system_user SET data_scope = 'ALL' WHERE user_id = 9601")
                .update();

        assertThat(databaseAuthService.loadAuthenticatedUser(9601L).dataScope()).isEqualTo("ALL");
    }

    @Test
    void portalRoleDefaultAppliesWhenIdentityCarriesNoDataScope() {
        // system_role.data_scope 为 NOT NULL，因此「角色没配范围」只可能出现在
        // 未携带数据范围的 bootstrap 身份上，此时按入口角色给默认值。
        assertThat(accessControlService.effectiveDataScope(
                new BootstrapIdentity(UserRole.WORKER, 9601L, null))).isEqualTo("SELF");
        assertThat(accessControlService.effectiveDataScope(
                new BootstrapIdentity(UserRole.DOCTOR, 9701L, 1L))).isEqualTo("CLINIC");
        assertThat(accessControlService.effectiveDataScope(
                new BootstrapIdentity(UserRole.ADMIN, 8001L, null))).isEqualTo("ALL");
    }

    @Test
    void csCannotAssignProcessesAfterWorkflowAssignIsRevoked() throws Exception {
        // PRD 11.3-03：非管理员不能修改工序链。A 批次撤销了 CS 上与实现不符的 workflow:assign 授权，
        // 改成纯权限码判定后 CS 仍必须被拒绝。
        mockMvc.perform(post("/orders/{orderId}/process-instance/assign", 1L)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", 8002L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chain_id\":1,\"assignments\":[]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void newFineGrainedRoleGetsAccessPurelyThroughConfiguration() {
        // 新增一个细分角色并授予权限码，全程不需要改 Java 代码。
        String roleCode = "TEAM_LEAD_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcClient.sql("""
                        INSERT INTO system_role (role_code, role_name, data_scope, status)
                        VALUES (:roleCode, '组长（测试）', 'SELF', 'ACTIVE')
                        """)
                .param("roleCode", roleCode)
                .update();
        long roleId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jdbcClient.sql("""
                        INSERT INTO system_role_permission (role_id, permission_id)
                        SELECT :roleId, permission_id
                        FROM system_permission
                        WHERE permission_code = 'check:read-internal'
                        """)
                .param("roleId", roleId)
                .update();
        jdbcClient.sql("INSERT INTO system_user_role (user_id, role_id) VALUES (9601, :roleId)")
                .param("roleId", roleId)
                .update();

        AuthenticatedUser user = databaseAuthService.loadAuthenticatedUser(9601L);
        assertThat(user.roles()).contains(roleCode);
        assertThat(user.permissions()).contains("check:read-internal");
    }

    private void revokeFromRole(String roleCode, String permissionCode) {
        jdbcClient.sql("""
                        DELETE rp
                        FROM system_role_permission rp
                        JOIN system_role r ON r.role_id = rp.role_id
                        JOIN system_permission p ON p.permission_id = rp.permission_id
                        WHERE r.role_code = :roleCode
                          AND p.permission_code = :permissionCode
                        """)
                .param("roleCode", roleCode)
                .param("permissionCode", permissionCode)
                .update();
    }
}
