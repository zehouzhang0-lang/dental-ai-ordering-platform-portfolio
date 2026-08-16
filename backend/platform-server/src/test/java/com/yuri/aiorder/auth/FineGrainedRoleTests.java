package com.yuri.aiorder.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.AccessControlService;
import com.yuri.aiorder.common.auth.AuthMenu;
import com.yuri.aiorder.common.auth.AuthenticatedUser;
import com.yuri.aiorder.common.auth.DatabaseAuthService;
import com.yuri.aiorder.common.auth.SystemConfigService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * TASK-034 B 批次：客户确认的细分角色与专项权限。
 *
 * <p>这些角色全部是 {@code system_role} 数据，不是枚举值——测试同时也在证明
 * 「新增一个角色不需要改 Java 代码」这条验收要求。
 */
@SpringBootTest
@Transactional
class FineGrainedRoleTests {

    private static final long WORKER_USER_ID = 9601L;
    private static final long CS_USER_ID = 8002L;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private DatabaseAuthService databaseAuthService;

    @Autowired
    private AccessControlService accessControlService;

    @Autowired
    private SystemConfigService systemConfigService;

    @Test
    void allTwentyFineGrainedRolesAreSeededAsConfigurationData() {
        List<String> roleCodes = jdbcClient.sql("""
                        SELECT role_code
                        FROM system_role
                        WHERE role_code NOT IN ('ADMIN', 'CS', 'WORKER', 'DOCTOR')
                        ORDER BY role_code
                        """)
                .query(String.class)
                .list();
        assertThat(roleCodes).hasSize(20)
                .contains("CS_MANAGER", "CS_SENIOR", "CS_AGENT", "CS_TRANSLATOR",
                        "CS_RECEIVER", "CS_SHIPPER",
                        "PROD_MANAGER", "PROD_SUPERVISOR", "PROD_TEAM_LEAD", "PROD_TECHNICIAN",
                        "PROD_QC", "PROD_FINAL_QC", "PROD_DATA_REVIEWER",
                        "CLINIC_ADMIN", "CLINIC_DOCTOR", "CLINIC_FRONTDESK", "CLINIC_ASSISTANT",
                        "ADMIN_MANAGER", "ADMIN_SUPERVISOR", "ADMIN_STAFF");
    }

    @Test
    void teamLeadDoesGateInspectionWhileQualityInspectorOnlyDoesSampling() {
        BootstrapIdentity teamLead = identityWithRolePermissions(UserRole.WORKER, "PROD_TEAM_LEAD");
        BootstrapIdentity qualityInspector = identityWithRolePermissions(UserRole.WORKER, "PROD_QC");

        // 组长：入检 / 出检与过程抽检都可以做。
        accessControlService.requireGateInspection(teamLead);
        accessControlService.requireSampleInspection(teamLead);

        // 质检员：只能做过程抽检，入检 / 出检必须被拒。
        accessControlService.requireSampleInspection(qualityInspector);
        assertThatForbidden(() -> accessControlService.requireGateInspection(qualityInspector));
    }

    @Test
    void internalReworkIsRegisteredByTeamLeadAndResponsibilityConfirmedByQualityInspector() {
        BootstrapIdentity teamLead = identityWithRolePermissions(UserRole.WORKER, "PROD_TEAM_LEAD");
        BootstrapIdentity qualityInspector = identityWithRolePermissions(UserRole.WORKER, "PROD_QC");

        assertThat(teamLead.hasPermission("rework:register-internal")).isTrue();
        assertThat(teamLead.hasPermission("rework:confirm-responsibility")).isFalse();

        assertThat(qualityInspector.hasPermission("rework:confirm-responsibility")).isTrue();
        assertThat(qualityInspector.hasPermission("rework:register-internal")).isFalse();
    }

    @Test
    void receiverAndShipperSeeOnlyTheirOwnScope() {
        BootstrapIdentity receiver = identityWithRolePermissions(UserRole.CS, "CS_RECEIVER");
        BootstrapIdentity shipper = identityWithRolePermissions(UserRole.CS, "CS_SHIPPER");

        assertThat(receiver.hasPermission("logistics:receive")).isTrue();
        assertThat(receiver.hasPermission("logistics:ship")).isFalse();
        assertThat(shipper.hasPermission("logistics:ship")).isTrue();
        assertThat(shipper.hasPermission("logistics:receive")).isFalse();

        // 收货 / 发货人员不碰客户资料、产品与经营看板。
        for (BootstrapIdentity identity : List.of(receiver, shipper)) {
            assertThat(identity.hasPermission("clinic:manage")).isFalse();
            assertThat(identity.hasPermission("product:manage")).isFalse();
            assertThat(identity.hasPermission("dashboard:read-sales")).isFalse();
            assertThatForbidden(() ->
                    accessControlService.requirePermission(identity, "clinic:manage", "denied"));
        }
    }

    @Test
    void csManagerSeesEverythingWhileAgentIsLimitedToOwnScope() {
        assertThat(dataScopeOf("CS_MANAGER")).isEqualTo("ALL");
        assertThat(dataScopeOf("CS_AGENT")).isEqualTo("SELF");

        BootstrapIdentity manager = identityWithRolePermissions(UserRole.CS, "CS_MANAGER");
        BootstrapIdentity agent = identityWithRolePermissions(UserRole.CS, "CS_AGENT");
        assertThat(manager.hasPermission("clinic:manage")).isTrue();
        assertThat(agent.hasPermission("clinic:manage")).isFalse();
    }

    @Test
    void adminDelegationOfProductionOperationIsDrivenByConfigurationSwitch() {
        BootstrapIdentity admin = identityWithRolePermissions(UserRole.ADMIN, "ADMIN");

        // 默认关闭：管理端不能代技工开工 / 完工。
        assertThat(systemConfigService.adminCanOperateProduction()).isFalse();
        assertThatForbidden(() -> accessControlService.requireProductionOperator(
                admin, 9601L, "denied", systemConfigService.adminCanOperateProduction()));

        setConfig(SystemConfigService.ADMIN_CAN_OPERATE_PRODUCTION, "true");

        // 客户澄清后只需改配置，不需要改代码。
        assertThat(systemConfigService.adminCanOperateProduction()).isTrue();
        accessControlService.requireProductionOperator(
                admin, 9601L, "denied", systemConfigService.adminCanOperateProduction());
    }

    @Test
    void pendingClarificationsAreExpressedAsSwitchesNotHardcodedBehaviour() {
        assertThat(systemConfigService.get(SystemConfigService.CS_SENIOR_ENABLED, "")).isEqualTo("true");
        assertThat(systemConfigService.get(SystemConfigService.PRODUCTION_DATA_REVIEWER_SUCCESSOR, ""))
                .isEqualTo("PROD_SUPERVISOR");

        // 生产资料审核员：客户已勾取消，角色以 INACTIVE 建档保留结构。
        assertThat(jdbcClient.sql("SELECT status FROM system_role WHERE role_code = 'PROD_DATA_REVIEWER'")
                .query(String.class)
                .single()).isEqualTo("INACTIVE");

        setConfig(SystemConfigService.PRODUCTION_DATA_REVIEWER_SUCCESSOR, "PROD_MANAGER");
        assertThat(systemConfigService.get(SystemConfigService.PRODUCTION_DATA_REVIEWER_SUCCESSOR, ""))
                .isEqualTo("PROD_MANAGER");
    }

    @Test
    void assigningFineGrainedRoleToUserDoesNotBreakPortalLogin() {
        grantRole(WORKER_USER_ID, "PROD_TEAM_LEAD");

        AuthenticatedUser user = databaseAuthService.loadAuthenticatedUser(WORKER_USER_ID);
        assertThat(user.roles()).contains("WORKER", "PROD_TEAM_LEAD");
        assertThat(user.identity().role()).isEqualTo(UserRole.WORKER);
        assertThat(user.permissions()).contains("check:gate-inspect");
    }

    @Test
    void portalAndFineGrainedRoleUseOnlyFineGrainedPermissionsAndScope() {
        AuthenticatedUser portalOnly = databaseAuthService.loadAuthenticatedUser(CS_USER_ID);
        assertThat(portalOnly.dataScope()).isEqualTo("ALL");
        assertThat(portalOnly.permissions()).contains("logistics:ship");

        grantRole(CS_USER_ID, "CS_AGENT");

        AuthenticatedUser agent = databaseAuthService.loadAuthenticatedUser(CS_USER_ID);
        assertThat(agent.roles()).contains("CS", "CS_AGENT");
        assertThat(agent.identity().role()).isEqualTo(UserRole.CS);
        assertThat(agent.dataScope()).isEqualTo("SELF");
        assertThat(agent.permissions()).contains("message:manage", "order:read-internal");
        assertThat(agent.permissions()).doesNotContain("logistics:ship", "clinic:manage", "product:manage");
        assertThat(agent.menus()).extracting(AuthMenu::menuCode)
                .contains("internal-orders")
                .doesNotContain("product-catalog");

        grantDirectPermission(CS_USER_ID, "product:manage");

        AuthenticatedUser agentWithDirectPermission = databaseAuthService.loadAuthenticatedUser(CS_USER_ID);
        assertThat(agentWithDirectPermission.permissions()).contains("product:manage");
        assertThat(agentWithDirectPermission.menus()).extracting(AuthMenu::menuCode)
                .contains("product-catalog");
    }

    @Test
    void inactiveRoleGrantsNothing() {
        grantRole(CS_USER_ID, "PROD_DATA_REVIEWER");

        AuthenticatedUser user = databaseAuthService.loadAuthenticatedUser(CS_USER_ID);
        assertThat(user.roles()).doesNotContain("PROD_DATA_REVIEWER");
        assertThat(user.permissions()).doesNotContain("production:review-data");
    }

    @Test
    void permissionListIsNotTruncatedWhenRoleHasManyCodes() {
        // 回归：原实现用 GROUP_CONCAT 拼权限码 CSV，MySQL group_concat_max_len 默认 1024 字节，
        // 权限码一多就静默截断。B 批次给管理员补齐权限码后正好越线，workflow:assign 被截掉且不报错。
        long grantedCount = jdbcClient.sql("""
                        SELECT COUNT(DISTINCT p.permission_code)
                        FROM system_user_role ur
                        JOIN system_role r ON r.role_id = ur.role_id AND r.status = 'ACTIVE'
                        JOIN system_role_permission rp ON rp.role_id = r.role_id
                        JOIN system_permission p ON p.permission_id = rp.permission_id AND p.status = 'ACTIVE'
                        WHERE ur.user_id = 8001
                        """)
                .query(Long.class)
                .single();

        AuthenticatedUser admin = databaseAuthService.loadAuthenticatedUser(8001L);
        assertThat(admin.permissions()).hasSize((int) grantedCount);
        assertThat(admin.permissions()).contains("workflow:assign");
        // 截断发生在 1KB 左右，因此权限码总长度必须确实越过这条线，这条回归才有意义。
        assertThat(String.join(",", admin.permissions()).length()).isGreaterThan(1024);
    }

    private String dataScopeOf(String roleCode) {
        return jdbcClient.sql("SELECT data_scope FROM system_role WHERE role_code = :roleCode")
                .param("roleCode", roleCode)
                .query(String.class)
                .single();
    }

    private BootstrapIdentity identityWithRolePermissions(UserRole portalRole, String roleCode) {
        List<String> permissions = jdbcClient.sql("""
                        SELECT p.permission_code
                        FROM system_role r
                        JOIN system_role_permission rp ON rp.role_id = r.role_id
                        JOIN system_permission p ON p.permission_id = rp.permission_id
                        WHERE r.role_code = :roleCode
                          AND p.status = 'ACTIVE'
                        """)
                .param("roleCode", roleCode)
                .query(String.class)
                .list();
        return new BootstrapIdentity(portalRole, 9999L, null, roleCode, Set.copyOf(permissions), "SELF");
    }

    private void grantRole(long userId, String roleCode) {
        jdbcClient.sql("""
                        INSERT IGNORE INTO system_user_role (user_id, role_id)
                        SELECT :userId, role_id FROM system_role WHERE role_code = :roleCode
                        """)
                .param("userId", userId)
                .param("roleCode", roleCode)
                .update();
    }

    private void grantDirectPermission(long userId, String permissionCode) {
        jdbcClient.sql("""
                        INSERT IGNORE INTO system_user_permission (user_id, permission_id)
                        SELECT :userId, permission_id
                        FROM system_permission
                        WHERE permission_code = :permissionCode
                        """)
                .param("userId", userId)
                .param("permissionCode", permissionCode)
                .update();
    }

    private void setConfig(String key, String value) {
        jdbcClient.sql("UPDATE system_config SET config_value = :value WHERE config_key = :key")
                .param("value", value)
                .param("key", key)
                .update();
    }

    private void assertThatForbidden(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected ResponseStatusException(403) but nothing was thrown");
        } catch (ResponseStatusException ex) {
            assertThat(ex.getStatusCode().value()).isEqualTo(403);
        }
    }
}
