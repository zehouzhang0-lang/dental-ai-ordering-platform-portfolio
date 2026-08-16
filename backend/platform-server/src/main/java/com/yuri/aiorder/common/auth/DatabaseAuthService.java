package com.yuri.aiorder.common.auth;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DatabaseAuthService {

    private static final List<UserRole> ROLE_PRIORITY = List.of(
            UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR);

    private final JdbcClient jdbcClient;
    private final PasswordHashService passwordHashService;

    public DatabaseAuthService(JdbcClient jdbcClient, PasswordHashService passwordHashService) {
        this.jdbcClient = jdbcClient;
        this.passwordHashService = passwordHashService;
    }

    public AuthenticatedUser authenticate(String username, String password) {
        UserAuthRow row = loadUser(username);
        if (!passwordHashService.matches(password, row.passwordHash())) {
            throw unauthorized();
        }
        return toAuthenticatedUser(row);
    }

    public AuthenticatedUser loadAuthenticatedUser(long userId) {
        return toAuthenticatedUser(loadUser(userId));
    }

    private AuthenticatedUser toAuthenticatedUser(UserAuthRow row) {
        List<String> roles = row.roleCodes();
        List<String> permissions = row.permissionCodes();
        boolean fineGrainedRolesOnly = hasFineGrainedRole(roles);
        UserRole primaryRole = primaryRole(roles);
        String dataScope = resolveDataScope(primaryRole, row.userDataScope(), row.dataScopes());
        BootstrapIdentity identity = new BootstrapIdentity(
                primaryRole,
                row.userId(),
                row.clinicId(),
                row.username(),
                Set.copyOf(permissions),
                dataScope);
        List<AuthMenu> menus = loadMenus(row.userId(), fineGrainedRolesOnly);
        return new AuthenticatedUser(
                row.username(),
                row.userId(),
                row.clinicId(),
                roles,
                permissions,
                menus,
                dataScope,
                identity);
    }

    public List<AuthMenu> loadMenus(BootstrapIdentity identity) {
        if (identity.userId() == null) {
            return List.of();
        }
        return loadMenus(identity.userId(), hasFineGrainedRole(identity.userId()));
    }

    private UserAuthRow loadUser(String username) {
        return loadUser("username", username);
    }

    private UserAuthRow loadUser(long userId) {
        return loadUser("user_id", userId);
    }

    /**
     * 分三次查询装配用户身份，而不是用一条带 GROUP_CONCAT 的大查询。
     *
     * <p>原实现把角色码、数据范围、权限码都用 {@code GROUP_CONCAT} 拼成 CSV。MySQL 的
     * {@code group_concat_max_len} 默认只有 1024 字节：权限码一多，字符串会被**静默截断**，
     * 用户于是莫名其妙少掉一批权限且不报任何错。TASK-034 B 批次给管理员补齐权限码后，
     * 管理员的权限串正好越过这条线，`workflow:assign` 被截掉。
     */
    private UserAuthRow loadUser(String identifierColumn, Object identifierValue) {
        try {
            UserBaseRow base = jdbcClient.sql("""
                            SELECT
                                u.user_id,
                                u.username,
                                u.password_hash,
                                u.clinic_id,
                                u.data_scope AS user_data_scope
                            FROM system_user u
                            WHERE u.%s = :identifier
                              AND u.status = 'ACTIVE'
                            """.formatted(identifierColumn))
                    .param("identifier", identifierValue)
                    .query((rs, rowNum) -> new UserBaseRow(
                            rs.getLong("user_id"),
                            rs.getString("username"),
                            rs.getString("password_hash"),
                            rs.getObject("clinic_id", Long.class),
                            rs.getString("user_data_scope")))
                    .single();

            List<String> roleCodes = jdbcClient.sql("""
                            SELECT r.role_code
                            FROM system_user_role ur
                            JOIN system_role r ON r.role_id = ur.role_id
                            WHERE ur.user_id = :userId
                              AND r.status = 'ACTIVE'
                            ORDER BY r.role_code
                            """)
                    .param("userId", base.userId())
                    .query(String.class)
                    .list();
            if (roleCodes.isEmpty()) {
                throw unauthorized();
            }
            boolean fineGrainedRolesOnly = hasFineGrainedRole(roleCodes);

            List<String> dataScopes = jdbcClient.sql("""
                            SELECT DISTINCT r.data_scope
                            FROM system_user_role ur
                            JOIN system_role r ON r.role_id = ur.role_id
                            WHERE ur.user_id = :userId
                              AND r.status = 'ACTIVE'
                              AND r.data_scope IS NOT NULL
                              AND (
                                  :fineGrainedRolesOnly = FALSE
                                  OR r.role_code NOT IN ('ADMIN', 'CS', 'WORKER', 'DOCTOR')
                              )
                            """)
                    .param("userId", base.userId())
                    .param("fineGrainedRolesOnly", fineGrainedRolesOnly)
                    .query(String.class)
                    .list();

            List<String> permissionCodes = jdbcClient.sql("""
                            SELECT DISTINCT p.permission_code
                            FROM system_permission p
                            JOIN (
                                SELECT role_permission.permission_id
                                FROM system_user_role role_user
                                JOIN system_role active_role
                                  ON active_role.role_id = role_user.role_id
                                 AND active_role.status = 'ACTIVE'
                                JOIN system_role_permission role_permission
                                  ON role_permission.role_id = active_role.role_id
                                WHERE role_user.user_id = :userId
                                  AND (
                                      :fineGrainedRolesOnly = FALSE
                                      OR active_role.role_code NOT IN ('ADMIN', 'CS', 'WORKER', 'DOCTOR')
                                  )
                                UNION
                                SELECT direct_permission.permission_id
                                FROM system_user_permission direct_permission
                                WHERE direct_permission.user_id = :userId
                            ) effective_permission
                              ON effective_permission.permission_id = p.permission_id
                            WHERE p.status = 'ACTIVE'
                            ORDER BY p.permission_code
                            """)
                    .param("userId", base.userId())
                    .param("fineGrainedRolesOnly", fineGrainedRolesOnly)
                    .query(String.class)
                    .list();

            return new UserAuthRow(
                    base.userId(),
                    base.username(),
                    base.passwordHash(),
                    base.clinicId(),
                    base.userDataScope(),
                    roleCodes,
                    dataScopes,
                    permissionCodes);
        } catch (EmptyResultDataAccessException ex) {
            throw unauthorized();
        }
    }

    /**
     * 解析入口角色（Portal）。
     *
     * <p>只有与 {@link UserRole} 同名的角色码才是入口角色；客户确认的细分角色（组长、终检员、收货人员……）
     * 是普通的 {@code system_role} 记录，会被忽略而不是让登录失败。
     * 原实现对每个角色码直接 {@code UserRole.valueOf}，一旦管理端新建一个细分角色并分配给用户，
     * 该用户就再也登录不进来——这与「新增角色不需要改 Java 代码」直接冲突。
     */
    private UserRole primaryRole(List<String> roles) {
        return roles.stream()
                .map(this::toPortalRole)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.comparingInt(ROLE_PRIORITY::indexOf))
                .orElseThrow(this::unauthorized);
    }

    private UserRole toPortalRole(String roleCode) {
        try {
            return UserRole.valueOf(roleCode);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean hasFineGrainedRole(List<String> roleCodes) {
        return roleCodes.stream().anyMatch(roleCode -> toPortalRole(roleCode) == null);
    }

    private boolean hasFineGrainedRole(long userId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM system_user_role user_role
                        JOIN system_role role ON role.role_id = user_role.role_id
                        WHERE user_role.user_id = :userId
                          AND role.status = 'ACTIVE'
                          AND role.role_code NOT IN ('ADMIN', 'CS', 'WORKER', 'DOCTOR')
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single() > 0;
    }

    /**
     * 数据范围解析顺序：用户级覆盖 &gt; 角色级配置 &gt; 入口角色默认值。
     *
     * <p>此前的实现把「入口角色是 ADMIN / CS 就返回 ALL」写在最前面，角色级 {@code system_role.data_scope}
     * 实际上永远被入口角色盖掉——客户要的「客服经理=全公司 / 普通客服=本人负责」在那种写法下无法配置出来。
     * 现在只有当该用户的所有角色都没有配置数据范围时，才回落到入口角色默认值。
     *
     * <p>用户持有细分角色时，入口角色只负责 Portal 选择，不参与业务权限或数据范围聚合；
     * 完全没有细分角色的历史账号仍使用入口角色配置保持兼容。多个细分角色之间仍取最宽范围，
     * 直到后续引入显式的「当前身份」选择。
     */
    private String resolveDataScope(UserRole primaryRole, String userDataScope, List<String> dataScopes) {
        String override = normalizeDataScope(userDataScope);
        if (override != null) {
            return override;
        }
        if (dataScopes.contains("ALL")) {
            return "ALL";
        }
        if (dataScopes.contains("CLINIC")) {
            return "CLINIC";
        }
        if (dataScopes.contains("SELF")) {
            return "SELF";
        }
        if (!dataScopes.isEmpty()) {
            return "NONE";
        }
        return switch (primaryRole) {
            case ADMIN, CS -> "ALL";
            case DOCTOR -> "CLINIC";
            case WORKER -> "SELF";
        };
    }

    private String normalizeDataScope(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    private List<AuthMenu> loadMenus(long userId, boolean fineGrainedRolesOnly) {
        return jdbcClient.sql("""
                        SELECT DISTINCT
                            m.menu_code,
                            m.menu_name,
                            m.menu_type,
                            m.route_path,
                            m.component_path,
                            m.permission_code,
                            m.icon,
                            m.sort_order
                        FROM system_user_role ur
                        JOIN system_role r ON r.role_id = ur.role_id
                        JOIN system_role_menu rm ON rm.role_id = r.role_id
                        JOIN system_menu m ON m.menu_id = rm.menu_id
                        WHERE ur.user_id = :userId
                          AND r.status = 'ACTIVE'
                          AND m.status = 'ACTIVE'
                          AND (
                              m.permission_code IS NULL
                              OR m.permission_code = ''
                              OR EXISTS (
                                  SELECT 1
                                  FROM system_permission effective_permission
                                  WHERE effective_permission.permission_code = m.permission_code
                                    AND effective_permission.status = 'ACTIVE'
                                    AND (
                                        EXISTS (
                                            SELECT 1
                                            FROM system_user_role permission_user_role
                                            JOIN system_role permission_role
                                              ON permission_role.role_id = permission_user_role.role_id
                                             AND permission_role.status = 'ACTIVE'
                                            JOIN system_role_permission role_permission
                                              ON role_permission.role_id = permission_role.role_id
                                            WHERE permission_user_role.user_id = ur.user_id
                                              AND role_permission.permission_id = effective_permission.permission_id
                                              AND (
                                                  :fineGrainedRolesOnly = FALSE
                                                  OR permission_role.role_code NOT IN ('ADMIN', 'CS', 'WORKER', 'DOCTOR')
                                              )
                                        )
                                        OR EXISTS (
                                            SELECT 1
                                            FROM system_user_permission user_permission
                                            WHERE user_permission.user_id = ur.user_id
                                              AND user_permission.permission_id = effective_permission.permission_id
                                        )
                                    )
                              )
                          )
                        ORDER BY m.sort_order, m.menu_code
                        """)
                .param("userId", userId)
                .param("fineGrainedRolesOnly", fineGrainedRolesOnly)
                .query((rs, rowNum) -> new AuthMenu(
                        rs.getString("menu_code"),
                        rs.getString("menu_name"),
                        rs.getString("menu_type"),
                        rs.getString("route_path"),
                        rs.getString("component_path"),
                        rs.getString("permission_code"),
                        rs.getString("icon"),
                        rs.getObject("sort_order", Integer.class)))
                .list();
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid username or password");
    }

    private record UserAuthRow(
            long userId,
            String username,
            String passwordHash,
            Long clinicId,
            String userDataScope,
            List<String> roleCodes,
            List<String> dataScopes,
            List<String> permissionCodes) {
    }

    private record UserBaseRow(
            long userId,
            String username,
            String passwordHash,
            Long clinicId,
            String userDataScope) {
    }
}
