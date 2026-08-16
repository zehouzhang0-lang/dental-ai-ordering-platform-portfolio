package com.yuri.aiorder.rbac;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.auth.AccessControlService;
import com.yuri.aiorder.common.auth.PasswordHashService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 管理端角色 / 权限 / 组织管理（TASK-034 C 批次）。
 *
 * <p>客户确认的授权边界在这里用两个可判定的数据字段实现，而不是写死角色名：
 * <ul>
 *   <li>{@code system_role.role_level}：数字越小权限越高。授权人只能授予**等级严格低于自己**的角色，
 *       于是「经理不能分配管理员级和经理级」自动成立，新增角色只需配等级。</li>
 *   <li>{@code rbac:cross-dept} 权限码：没有它就只能操作与自己同部门的用户，于是「主管不能跨部门」成立。</li>
 * </ul>
 *
 * <p>所有写操作都写入 {@code system_rbac_audit}，记录操作人、时间、对象与修改前后内容。
 * 密码只能重置，任何接口都不返回口令或散列值。
 */
@Service
public class RbacAdminService {

    private static final int PLATFORM_ADMIN_LEVEL = 0;
    private static final int DEFAULT_ROLE_LEVEL = 30;
    private static final List<String> PORTAL_ROLE_CODES = List.of("ADMIN", "CS", "WORKER", "DOCTOR");

    private final JdbcClient jdbcClient;
    private final AccessControlService accessControlService;
    private final PasswordHashService passwordHashService;
    private final ObjectMapper objectMapper;

    public RbacAdminService(
            JdbcClient jdbcClient,
            AccessControlService accessControlService,
            PasswordHashService passwordHashService,
            ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.accessControlService = accessControlService;
        this.passwordHashService = passwordHashService;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ 角色

    public List<RbacRoleResponse> listRoles(BootstrapIdentity identity) {
        accessControlService.requireAnyPermission(
                identity, "role list requires rbac:matrix:read", "rbac:matrix:read", "rbac:role:manage");
        return jdbcClient.sql("""
                        SELECT r.role_id, r.role_code, r.role_name, r.data_scope, r.role_level, r.status, r.remark,
                               (SELECT COUNT(*) FROM system_role_permission rp WHERE rp.role_id = r.role_id)
                                   AS permission_count,
                               (SELECT COUNT(*) FROM system_role_menu rm WHERE rm.role_id = r.role_id)
                                   AS menu_count,
                               (SELECT COUNT(*) FROM system_user_role ur WHERE ur.role_id = r.role_id)
                                   AS user_count
                        FROM system_role r
                        ORDER BY r.role_level ASC, r.role_code ASC
                        """)
                .query((rs, rowNum) -> new RbacRoleResponse(
                        rs.getLong("role_id"),
                        rs.getString("role_code"),
                        rs.getString("role_name"),
                        rs.getString("data_scope"),
                        rs.getInt("role_level"),
                        rs.getString("status"),
                        rs.getString("remark"),
                        rs.getInt("permission_count"),
                        rs.getInt("menu_count"),
                        rs.getInt("user_count")))
                .list();
    }

    @Transactional
    public RbacRoleResponse createRole(RbacRoleRequest request, BootstrapIdentity identity) {
        accessControlService.requirePermission(identity, "rbac:role:manage", "role creation requires rbac:role:manage");
        String roleCode = normalizeRequired(request.roleCode(), "role_code").toUpperCase(Locale.ROOT);
        String roleName = normalizeRequired(request.roleName(), "role_name");
        int roleLevel = request.roleLevel() == null ? DEFAULT_ROLE_LEVEL : request.roleLevel();
        requireCanGrantLevel(identity, roleLevel);
        if (roleExists(roleCode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "role_code already exists");
        }
        jdbcClient.sql("""
                        INSERT INTO system_role (role_code, role_name, data_scope, role_level, remark, status)
                        VALUES (:roleCode, :roleName, :dataScope, :roleLevel, :remark, 'ACTIVE')
                        """)
                .param("roleCode", roleCode)
                .param("roleName", roleName)
                .param("dataScope", normalizeDataScope(request.dataScope()))
                .param("roleLevel", roleLevel)
                .param("remark", blankToNull(request.remark()))
                .update();
        long roleId = lastInsertId();
        RbacRoleResponse created = loadRole(roleId);
        audit("ROLE", roleId, roleCode, "CREATE", null, created, identity, request.reason());
        return created;
    }

    @Transactional
    public RbacRoleResponse updateRole(long roleId, RbacRoleRequest request, BootstrapIdentity identity) {
        accessControlService.requirePermission(identity, "rbac:role:manage", "role update requires rbac:role:manage");
        RbacRoleResponse before = loadRole(roleId);
        requireCanGrantLevel(identity, before.roleLevel());
        int roleLevel = request.roleLevel() == null ? before.roleLevel() : request.roleLevel();
        requireCanGrantLevel(identity, roleLevel);
        jdbcClient.sql("""
                        UPDATE system_role
                        SET role_name = :roleName,
                            data_scope = :dataScope,
                            role_level = :roleLevel,
                            remark = :remark
                        WHERE role_id = :roleId
                        """)
                .param("roleName", request.roleName() == null ? before.roleName() : request.roleName().trim())
                .param("dataScope", request.dataScope() == null
                        ? before.dataScope()
                        : normalizeDataScope(request.dataScope()))
                .param("roleLevel", roleLevel)
                .param("remark", blankToNull(request.remark()))
                .param("roleId", roleId)
                .update();
        RbacRoleResponse after = loadRole(roleId);
        audit("ROLE", roleId, after.roleCode(), "UPDATE", before, after, identity, request.reason());
        return after;
    }

    @Transactional
    public RbacRoleResponse updateRoleStatus(long roleId, String status, String reason, BootstrapIdentity identity) {
        accessControlService.requirePermission(identity, "rbac:role:manage", "role status requires rbac:role:manage");
        String normalized = normalizeRequired(status, "status").toUpperCase(Locale.ROOT);
        if (!List.of("ACTIVE", "INACTIVE").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be ACTIVE or INACTIVE");
        }
        RbacRoleResponse before = loadRole(roleId);
        requireCanGrantLevel(identity, before.roleLevel());
        if (PORTAL_ROLE_CODES.contains(before.roleCode()) && "INACTIVE".equals(normalized)) {
            // 停用入口角色会让对应端所有账号登录不进来，属不可逆的误操作。
            throw new ResponseStatusException(HttpStatus.CONFLICT, "portal role cannot be disabled");
        }
        jdbcClient.sql("UPDATE system_role SET status = :status WHERE role_id = :roleId")
                .param("status", normalized)
                .param("roleId", roleId)
                .update();
        RbacRoleResponse after = loadRole(roleId);
        audit("ROLE", roleId, after.roleCode(), "STATUS_CHANGE", before, after, identity, reason);
        return after;
    }

    @Transactional
    public RbacRolePermissionResponse updateRolePermissions(
            long roleId, RbacRolePermissionRequest request, BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, "rbac:permission:assign", "permission assignment requires rbac:permission:assign");
        RbacRoleResponse role = loadRole(roleId);
        requireCanGrantLevel(identity, role.roleLevel());
        requireGrantableCodes(identity, request.permissionCodes());

        RbacRolePermissionResponse before = loadRolePermissions(roleId);
        jdbcClient.sql("DELETE FROM system_role_permission WHERE role_id = :roleId")
                .param("roleId", roleId)
                .update();
        for (String code : nullToEmpty(request.permissionCodes())) {
            jdbcClient.sql("""
                            INSERT IGNORE INTO system_role_permission (role_id, permission_id)
                            SELECT :roleId, permission_id FROM system_permission
                            WHERE permission_code = :code AND status = 'ACTIVE'
                            """)
                    .param("roleId", roleId)
                    .param("code", code)
                    .update();
        }
        if (request.menuCodes() != null) {
            jdbcClient.sql("DELETE FROM system_role_menu WHERE role_id = :roleId")
                    .param("roleId", roleId)
                    .update();
            for (String code : request.menuCodes()) {
                jdbcClient.sql("""
                                INSERT IGNORE INTO system_role_menu (role_id, menu_id)
                                SELECT :roleId, menu_id FROM system_menu
                                WHERE menu_code = :code AND status = 'ACTIVE'
                                """)
                        .param("roleId", roleId)
                        .param("code", code)
                        .update();
            }
        }
        if (request.dataScope() != null) {
            jdbcClient.sql("UPDATE system_role SET data_scope = :dataScope WHERE role_id = :roleId")
                    .param("dataScope", normalizeDataScope(request.dataScope()))
                    .param("roleId", roleId)
                    .update();
        }
        RbacRolePermissionResponse after = loadRolePermissions(roleId);
        audit("ROLE_PERMISSION", roleId, role.roleCode(), "ASSIGN", before, after, identity, request.reason());
        return after;
    }

    public RbacRolePermissionResponse getRolePermissions(long roleId, BootstrapIdentity identity) {
        accessControlService.requireAnyPermission(
                identity, "role detail requires rbac:matrix:read", "rbac:matrix:read", "rbac:permission:assign");
        return loadRolePermissions(roleId);
    }

    public List<RbacPermissionResponse> listPermissions(BootstrapIdentity identity) {
        accessControlService.requireAnyPermission(
                identity, "permission list requires rbac:matrix:read", "rbac:matrix:read", "rbac:permission:assign");
        return jdbcClient.sql("""
                        SELECT permission_id, permission_code, permission_name, module_code, status
                        FROM system_permission
                        WHERE status = 'ACTIVE'
                        ORDER BY module_code, permission_code
                        """)
                .query((rs, rowNum) -> new RbacPermissionResponse(
                        rs.getLong("permission_id"),
                        rs.getString("permission_code"),
                        rs.getString("permission_name"),
                        rs.getString("module_code"),
                        rs.getString("status")))
                .list();
    }

    // ------------------------------------------------------------------ 组织

    public List<RbacDeptResponse> listDepartments(BootstrapIdentity identity) {
        accessControlService.requireAnyPermission(
                identity, "org list requires rbac:matrix:read", "rbac:matrix:read", "rbac:org:manage");
        return jdbcClient.sql("""
                        SELECT d.dept_id, d.parent_id, d.dept_code, d.dept_name, d.sort_order, d.status,
                               (SELECT COUNT(*) FROM system_user u WHERE u.dept_id = d.dept_id) AS member_count
                        FROM system_dept d
                        ORDER BY COALESCE(d.parent_id, d.dept_id), d.sort_order, d.dept_id
                        """)
                .query((rs, rowNum) -> new RbacDeptResponse(
                        rs.getLong("dept_id"),
                        rs.getObject("parent_id", Long.class),
                        rs.getString("dept_code"),
                        rs.getString("dept_name"),
                        rs.getInt("sort_order"),
                        rs.getString("status"),
                        rs.getInt("member_count")))
                .list();
    }

    @Transactional
    public RbacDeptResponse saveDepartment(Long deptId, RbacDeptRequest request, BootstrapIdentity identity) {
        accessControlService.requirePermission(identity, "rbac:org:manage", "org management requires rbac:org:manage");
        if (deptId == null) {
            String deptCode = normalizeRequired(request.deptCode(), "dept_code").toUpperCase(Locale.ROOT);
            long created = nextId("system_dept", "dept_id");
            jdbcClient.sql("""
                            INSERT INTO system_dept (dept_id, parent_id, dept_code, dept_name, sort_order, status)
                            VALUES (:deptId, :parentId, :deptCode, :deptName, :sortOrder, 'ACTIVE')
                            """)
                    .param("deptId", created)
                    .param("parentId", request.parentId())
                    .param("deptCode", deptCode)
                    .param("deptName", normalizeRequired(request.deptName(), "dept_name"))
                    .param("sortOrder", request.sortOrder() == null ? 0 : request.sortOrder())
                    .update();
            RbacDeptResponse after = loadDepartment(created);
            audit("DEPT", created, deptCode, "CREATE", null, after, identity, request.reason());
            return after;
        }
        RbacDeptResponse before = loadDepartment(deptId);
        requireNotSelfParent(deptId, request.parentId());
        jdbcClient.sql("""
                        UPDATE system_dept
                        SET parent_id = :parentId,
                            dept_name = :deptName,
                            sort_order = :sortOrder,
                            status = :status
                        WHERE dept_id = :deptId
                        """)
                .param("parentId", request.parentId())
                .param("deptName", request.deptName() == null ? before.deptName() : request.deptName().trim())
                .param("sortOrder", request.sortOrder() == null ? before.sortOrder() : request.sortOrder())
                .param("status", request.status() == null ? before.status() : request.status().toUpperCase(Locale.ROOT))
                .param("deptId", deptId)
                .update();
        RbacDeptResponse after = loadDepartment(deptId);
        audit("DEPT", deptId, after.deptCode(), "UPDATE", before, after, identity, request.reason());
        return after;
    }

    public List<RbacPostResponse> listPosts(BootstrapIdentity identity) {
        accessControlService.requireAnyPermission(
                identity, "post list requires rbac:matrix:read", "rbac:matrix:read", "rbac:org:manage");
        return jdbcClient.sql("""
                        SELECT post_id, post_code, post_name, sort_order, status
                        FROM system_post
                        ORDER BY sort_order, post_id
                        """)
                .query((rs, rowNum) -> new RbacPostResponse(
                        rs.getLong("post_id"),
                        rs.getString("post_code"),
                        rs.getString("post_name"),
                        rs.getInt("sort_order"),
                        rs.getString("status")))
                .list();
    }

    @Transactional
    public RbacPostResponse savePost(Long postId, RbacPostRequest request, BootstrapIdentity identity) {
        accessControlService.requirePermission(identity, "rbac:org:manage", "org management requires rbac:org:manage");
        if (postId == null) {
            String postCode = normalizeRequired(request.postCode(), "post_code").toUpperCase(Locale.ROOT);
            long created = nextId("system_post", "post_id");
            jdbcClient.sql("""
                            INSERT INTO system_post (post_id, post_code, post_name, sort_order, status)
                            VALUES (:postId, :postCode, :postName, :sortOrder, 'ACTIVE')
                            """)
                    .param("postId", created)
                    .param("postCode", postCode)
                    .param("postName", normalizeRequired(request.postName(), "post_name"))
                    .param("sortOrder", request.sortOrder() == null ? 0 : request.sortOrder())
                    .update();
            RbacPostResponse after = loadPost(created);
            audit("POST", created, postCode, "CREATE", null, after, identity, request.reason());
            return after;
        }
        RbacPostResponse before = loadPost(postId);
        jdbcClient.sql("""
                        UPDATE system_post
                        SET post_name = :postName, sort_order = :sortOrder, status = :status
                        WHERE post_id = :postId
                        """)
                .param("postName", request.postName() == null ? before.postName() : request.postName().trim())
                .param("sortOrder", request.sortOrder() == null ? before.sortOrder() : request.sortOrder())
                .param("status", request.status() == null ? before.status() : request.status().toUpperCase(Locale.ROOT))
                .param("postId", postId)
                .update();
        RbacPostResponse after = loadPost(postId);
        audit("POST", postId, after.postCode(), "UPDATE", before, after, identity, request.reason());
        return after;
    }

    // ------------------------------------------------------------------ 用户

    public List<RbacUserResponse> listUsers(BootstrapIdentity identity) {
        accessControlService.requireAnyPermission(
                identity, "user list requires rbac:matrix:read", "rbac:matrix:read", "rbac:user:assign");
        return jdbcClient.sql("""
                        SELECT u.user_id, u.username, u.display_name, u.status, u.dept_id, d.dept_name,
                               u.data_scope AS user_data_scope
                        FROM system_user u
                        LEFT JOIN system_dept d ON d.dept_id = u.dept_id
                        ORDER BY u.user_id
                        """)
                .query((rs, rowNum) -> {
                    long userId = rs.getLong("user_id");
                    return new RbacUserResponse(
                            userId,
                            rs.getString("username"),
                            rs.getString("display_name"),
                            rs.getString("status"),
                            rs.getObject("dept_id", Long.class),
                            rs.getString("dept_name"),
                            rs.getString("user_data_scope"),
                            loadUserRoleCodes(userId),
                            loadUserPostCodes(userId));
                })
                .list();
    }

    @Transactional
    public RbacUserResponse assignUser(long userId, RbacUserAssignRequest request, BootstrapIdentity identity) {
        accessControlService.requirePermission(identity, "rbac:user:assign", "user assignment requires rbac:user:assign");
        RbacUserResponse before = loadUser(userId);
        requireSameDeptScope(identity, before.deptId());

        // 入口角色决定「从哪个端登录」，不通过这个接口变更：非平台管理员的请求里，
        // 目标用户已有的入口角色一律保留，否则一次普通的角色调整会把人的登录入口删掉。
        List<String> targetRoles = new java.util.ArrayList<>(nullToEmpty(request.roleCodes()));
        if (request.roleCodes() != null && actorLevel(identity) != PLATFORM_ADMIN_LEVEL) {
            for (String existing : before.roleCodes()) {
                if (PORTAL_ROLE_CODES.contains(existing) && !targetRoles.contains(existing)) {
                    targetRoles.add(existing);
                }
            }
        }
        for (String roleCode : targetRoles) {
            if (!before.roleCodes().contains(roleCode)) {
                requireCanGrantLevel(identity, roleLevelOf(roleCode));
            }
        }
        for (String roleCode : before.roleCodes()) {
            // 收回一个自己无权授予的角色同样属于越权。
            if (!targetRoles.contains(roleCode)) {
                requireCanGrantLevel(identity, roleLevelOf(roleCode));
            }
        }

        if (request.roleCodes() != null) {
            jdbcClient.sql("DELETE FROM system_user_role WHERE user_id = :userId")
                    .param("userId", userId)
                    .update();
            for (String roleCode : targetRoles) {
                jdbcClient.sql("""
                                INSERT IGNORE INTO system_user_role (user_id, role_id)
                                SELECT :userId, role_id FROM system_role WHERE role_code = :roleCode
                                """)
                        .param("userId", userId)
                        .param("roleCode", roleCode)
                        .update();
            }
        }
        if (request.postCodes() != null) {
            jdbcClient.sql("DELETE FROM system_user_post WHERE user_id = :userId")
                    .param("userId", userId)
                    .update();
            for (String postCode : request.postCodes()) {
                jdbcClient.sql("""
                                INSERT IGNORE INTO system_user_post (user_id, post_id)
                                SELECT :userId, post_id FROM system_post WHERE post_code = :postCode
                                """)
                        .param("userId", userId)
                        .param("postCode", postCode)
                        .update();
            }
        }
        if (request.deptId() != null) {
            requireSameDeptScope(identity, request.deptId());
            jdbcClient.sql("UPDATE system_user SET dept_id = :deptId WHERE user_id = :userId")
                    .param("deptId", request.deptId())
                    .param("userId", userId)
                    .update();
        }
        if (request.dataScope() != null) {
            jdbcClient.sql("UPDATE system_user SET data_scope = :dataScope WHERE user_id = :userId")
                    .param("dataScope", request.dataScope().isBlank() ? null : normalizeDataScope(request.dataScope()))
                    .param("userId", userId)
                    .update();
        }
        RbacUserResponse after = loadUser(userId);
        audit("USER_ASSIGNMENT", userId, after.username(), "ASSIGN", before, after, identity, request.reason());
        return after;
    }

    @Transactional
    public RbacUserResponse updateUserStatus(long userId, String status, String reason, BootstrapIdentity identity) {
        accessControlService.requirePermission(identity, "account:disable", "account status requires account:disable");
        String normalized = normalizeRequired(status, "status").toUpperCase(Locale.ROOT);
        if (!List.of("ACTIVE", "DISABLED", "LOCKED").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported account status");
        }
        RbacUserResponse before = loadUser(userId);
        requireSameDeptScope(identity, before.deptId());
        jdbcClient.sql("UPDATE system_user SET status = :status WHERE user_id = :userId")
                .param("status", normalized)
                .param("userId", userId)
                .update();
        RbacUserResponse after = loadUser(userId);
        audit("ACCOUNT", userId, after.username(), "STATUS_CHANGE", before, after, identity, reason);
        return after;
    }

    /** 密码只能重置不能查看：接口既不接收也不返回明文或散列值，只返回一次性初始口令由管理员线下转交。 */
    @Transactional
    public RbacPasswordResetResponse resetPassword(long userId, String reason, BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, "account:reset-password", "password reset requires account:reset-password");
        RbacUserResponse target = loadUser(userId);
        requireSameDeptScope(identity, target.deptId());
        String temporaryPassword = "Reset-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbcClient.sql("UPDATE system_user SET password_hash = :passwordHash WHERE user_id = :userId")
                .param("passwordHash", passwordHashService.hash(temporaryPassword))
                .param("userId", userId)
                .update();
        // 审计只记录「发生过重置」，不记录口令本身。
        audit("ACCOUNT", userId, target.username(), "PASSWORD_RESET", null, null, identity, reason);
        return new RbacPasswordResetResponse(userId, target.username(), temporaryPassword);
    }

    // ------------------------------------------------------------------ 矩阵与审计

    public RbacMatrixResponse getPermissionMatrix(BootstrapIdentity identity) {
        accessControlService.requirePermission(identity, "rbac:matrix:read", "matrix requires rbac:matrix:read");
        List<RbacRoleResponse> roles = jdbcClient.sql("""
                        SELECT r.role_id, r.role_code, r.role_name, r.data_scope, r.role_level, r.status, r.remark,
                               0 AS permission_count, 0 AS menu_count, 0 AS user_count
                        FROM system_role r
                        WHERE r.status = 'ACTIVE'
                        ORDER BY r.role_level, r.role_code
                        """)
                .query((rs, rowNum) -> new RbacRoleResponse(
                        rs.getLong("role_id"),
                        rs.getString("role_code"),
                        rs.getString("role_name"),
                        rs.getString("data_scope"),
                        rs.getInt("role_level"),
                        rs.getString("status"),
                        rs.getString("remark"),
                        0, 0, 0))
                .list();
        Map<String, List<String>> permissionsByRole = new LinkedHashMap<>();
        Map<String, List<String>> menusByRole = new LinkedHashMap<>();
        for (RbacRoleResponse role : roles) {
            permissionsByRole.put(role.roleCode(), loadRolePermissionCodes(role.roleId()));
            menusByRole.put(role.roleCode(), loadRoleMenuCodes(role.roleId()));
        }
        return new RbacMatrixResponse(roles, listPermissions(identity), permissionsByRole, menusByRole);
    }

    public List<RbacAuditResponse> listAudits(BootstrapIdentity identity, String entityType, int limit) {
        accessControlService.requirePermission(identity, "rbac:matrix:read", "audit requires rbac:matrix:read");
        int safeLimit = Math.max(1, Math.min(limit, 200));
        // 结尾必须带换行：文本块拼接时紧跟着 ORDER BY，否则会拼出 ":entityTypeORDER" 这种坏参数名。
        String filter = entityType == null || entityType.isBlank() ? "" : " AND entity_type = :entityType\n";
        JdbcClient.StatementSpec spec = jdbcClient.sql("""
                        SELECT audit_id, entity_type, entity_id, entity_label, action_type,
                               CAST(before_value AS CHAR) AS before_value,
                               CAST(after_value AS CHAR) AS after_value,
                               operator_user_id, operator_username, reason, created_at
                        FROM system_rbac_audit
                        WHERE 1 = 1
                        """ + filter + """
                        ORDER BY audit_id DESC
                        """
                // Java 文本块会把每行的行尾空格吃掉，所以 LIMIT 必须显式拼一个空格，
                // 否则会拼成 "LIMIT50" 这种坏 SQL。
                + " LIMIT " + safeLimit);
        if (!filter.isEmpty()) {
            spec = spec.param("entityType", entityType.trim().toUpperCase(Locale.ROOT));
        }
        return spec.query((rs, rowNum) -> new RbacAuditResponse(
                        rs.getLong("audit_id"),
                        rs.getString("entity_type"),
                        rs.getObject("entity_id", Long.class),
                        rs.getString("entity_label"),
                        rs.getString("action_type"),
                        rs.getString("before_value"),
                        rs.getString("after_value"),
                        rs.getObject("operator_user_id", Long.class),
                        rs.getString("operator_username"),
                        rs.getString("reason"),
                        rs.getObject("created_at", java.time.LocalDateTime.class)))
                .list();
    }

    // ------------------------------------------------------------------ 授权边界

    /**
     * 授权人只能授予等级严格低于自己的角色。管理员（等级 0）不受此限，否则没有人能授予管理员级。
     */
    private void requireCanGrantLevel(BootstrapIdentity identity, int targetLevel) {
        int actorLevel = actorLevel(identity);
        if (actorLevel == PLATFORM_ADMIN_LEVEL) {
            return;
        }
        if (targetLevel <= actorLevel) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "cannot grant a role at or above your own authorization level");
        }
    }

    private void requireSameDeptScope(BootstrapIdentity identity, Long targetDeptId) {
        if (identity.hasPermission("rbac:cross-dept")) {
            return;
        }
        Long actorDeptId = identity.userId() == null ? null : jdbcClient
                .sql("SELECT dept_id FROM system_user WHERE user_id = :userId")
                .param("userId", identity.userId())
                .query(Long.class)
                .optional()
                .orElse(null);
        if (actorDeptId == null || targetDeptId == null || !actorDeptId.equals(targetDeptId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "cross-department assignment requires rbac:cross-dept");
        }
    }

    /** 不能把自己没有的权限码授予别人。管理员不受限。 */
    private void requireGrantableCodes(BootstrapIdentity identity, List<String> permissionCodes) {
        if (actorLevel(identity) == PLATFORM_ADMIN_LEVEL) {
            return;
        }
        for (String code : nullToEmpty(permissionCodes)) {
            if (!identity.hasPermission(code)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "cannot grant permission code you do not hold: " + code);
            }
        }
    }

    /**
     * 计算授权人自身的授权等级。
     *
     * <p>关键点：**入口角色不参与自身等级计算**。每个用户都持有一个入口角色，而 CS / WORKER / DOCTOR
     * 的 {@code role_level} 是 0（表示「只有管理员能授予入口角色」）。若把它们算进来，
     * 任何客服用户的 MIN 都会是 0，于是人人都被当成平台管理员，全部授权边界失效。
     * ADMIN 例外：持有 ADMIN 入口角色本身就意味着是平台管理员。
     */
    private int actorLevel(BootstrapIdentity identity) {
        if (identity.userId() == null) {
            return DEFAULT_ROLE_LEVEL;
        }
        return jdbcClient.sql("""
                        SELECT MIN(r.role_level)
                        FROM system_user_role ur
                        JOIN system_role r ON r.role_id = ur.role_id
                        WHERE ur.user_id = :userId
                          AND r.status = 'ACTIVE'
                          AND r.role_code NOT IN ('CS', 'WORKER', 'DOCTOR')
                        """)
                .param("userId", identity.userId())
                .query(Integer.class)
                .optional()
                .orElse(DEFAULT_ROLE_LEVEL);
    }

    private int roleLevelOf(String roleCode) {
        return jdbcClient.sql("SELECT role_level FROM system_role WHERE role_code = :roleCode")
                .param("roleCode", roleCode)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown role_code: " + roleCode));
    }

    // ------------------------------------------------------------------ 内部装配

    private RbacRoleResponse loadRole(long roleId) {
        return jdbcClient.sql("""
                        SELECT r.role_id, r.role_code, r.role_name, r.data_scope, r.role_level, r.status, r.remark,
                               (SELECT COUNT(*) FROM system_role_permission rp WHERE rp.role_id = r.role_id)
                                   AS permission_count,
                               (SELECT COUNT(*) FROM system_role_menu rm WHERE rm.role_id = r.role_id) AS menu_count,
                               (SELECT COUNT(*) FROM system_user_role ur WHERE ur.role_id = r.role_id) AS user_count
                        FROM system_role r
                        WHERE r.role_id = :roleId
                        """)
                .param("roleId", roleId)
                .query((rs, rowNum) -> new RbacRoleResponse(
                        rs.getLong("role_id"),
                        rs.getString("role_code"),
                        rs.getString("role_name"),
                        rs.getString("data_scope"),
                        rs.getInt("role_level"),
                        rs.getString("status"),
                        rs.getString("remark"),
                        rs.getInt("permission_count"),
                        rs.getInt("menu_count"),
                        rs.getInt("user_count")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "role not found"));
    }

    private RbacRolePermissionResponse loadRolePermissions(long roleId) {
        RbacRoleResponse role = loadRole(roleId);
        return new RbacRolePermissionResponse(
                role.roleId(),
                role.roleCode(),
                role.dataScope(),
                loadRolePermissionCodes(roleId),
                loadRoleMenuCodes(roleId));
    }

    private List<String> loadRolePermissionCodes(long roleId) {
        return jdbcClient.sql("""
                        SELECT p.permission_code
                        FROM system_role_permission rp
                        JOIN system_permission p ON p.permission_id = rp.permission_id
                        WHERE rp.role_id = :roleId
                        ORDER BY p.permission_code
                        """)
                .param("roleId", roleId)
                .query(String.class)
                .list();
    }

    private List<String> loadRoleMenuCodes(long roleId) {
        return jdbcClient.sql("""
                        SELECT m.menu_code
                        FROM system_role_menu rm
                        JOIN system_menu m ON m.menu_id = rm.menu_id
                        WHERE rm.role_id = :roleId
                        ORDER BY m.sort_order, m.menu_code
                        """)
                .param("roleId", roleId)
                .query(String.class)
                .list();
    }

    private List<String> loadUserRoleCodes(long userId) {
        return jdbcClient.sql("""
                        SELECT r.role_code
                        FROM system_user_role ur
                        JOIN system_role r ON r.role_id = ur.role_id
                        WHERE ur.user_id = :userId
                        ORDER BY r.role_level, r.role_code
                        """)
                .param("userId", userId)
                .query(String.class)
                .list();
    }

    private List<String> loadUserPostCodes(long userId) {
        return jdbcClient.sql("""
                        SELECT p.post_code
                        FROM system_user_post up
                        JOIN system_post p ON p.post_id = up.post_id
                        WHERE up.user_id = :userId
                        ORDER BY p.sort_order, p.post_code
                        """)
                .param("userId", userId)
                .query(String.class)
                .list();
    }

    private RbacUserResponse loadUser(long userId) {
        return jdbcClient.sql("""
                        SELECT u.user_id, u.username, u.display_name, u.status, u.dept_id, d.dept_name,
                               u.data_scope AS user_data_scope
                        FROM system_user u
                        LEFT JOIN system_dept d ON d.dept_id = u.dept_id
                        WHERE u.user_id = :userId
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> new RbacUserResponse(
                        rs.getLong("user_id"),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getString("status"),
                        rs.getObject("dept_id", Long.class),
                        rs.getString("dept_name"),
                        rs.getString("user_data_scope"),
                        loadUserRoleCodes(userId),
                        loadUserPostCodes(userId)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
    }

    private RbacDeptResponse loadDepartment(long deptId) {
        return jdbcClient.sql("""
                        SELECT d.dept_id, d.parent_id, d.dept_code, d.dept_name, d.sort_order, d.status,
                               (SELECT COUNT(*) FROM system_user u WHERE u.dept_id = d.dept_id) AS member_count
                        FROM system_dept d
                        WHERE d.dept_id = :deptId
                        """)
                .param("deptId", deptId)
                .query((rs, rowNum) -> new RbacDeptResponse(
                        rs.getLong("dept_id"),
                        rs.getObject("parent_id", Long.class),
                        rs.getString("dept_code"),
                        rs.getString("dept_name"),
                        rs.getInt("sort_order"),
                        rs.getString("status"),
                        rs.getInt("member_count")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "department not found"));
    }

    private RbacPostResponse loadPost(long postId) {
        return jdbcClient.sql("""
                        SELECT post_id, post_code, post_name, sort_order, status
                        FROM system_post
                        WHERE post_id = :postId
                        """)
                .param("postId", postId)
                .query((rs, rowNum) -> new RbacPostResponse(
                        rs.getLong("post_id"),
                        rs.getString("post_code"),
                        rs.getString("post_name"),
                        rs.getInt("sort_order"),
                        rs.getString("status")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "post not found"));
    }

    private void requireNotSelfParent(long deptId, Long parentId) {
        if (parentId != null && parentId == deptId) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "department cannot be its own parent");
        }
    }

    private void audit(
            String entityType,
            Long entityId,
            String entityLabel,
            String actionType,
            Object before,
            Object after,
            BootstrapIdentity identity,
            String reason) {
        jdbcClient.sql("""
                        INSERT INTO system_rbac_audit
                            (entity_type, entity_id, entity_label, action_type, before_value, after_value,
                             operator_user_id, operator_username, reason)
                        VALUES
                            (:entityType, :entityId, :entityLabel, :actionType,
                             CAST(:beforeValue AS JSON), CAST(:afterValue AS JSON),
                             :operatorUserId, :operatorUsername, :reason)
                        """)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("entityLabel", entityLabel)
                .param("actionType", actionType)
                .param("beforeValue", toJson(before))
                .param("afterValue", toJson(after))
                .param("operatorUserId", identity.userId())
                .param("operatorUsername", operatorUsername(identity))
                .param("reason", blankToNull(reason))
                .update();
    }

    /** 审计必须能追到人：身份未携带用户名时（例如 bootstrap header 链路）按 user_id 回查。 */
    private String operatorUsername(BootstrapIdentity identity) {
        if (identity.username() != null && !identity.username().isBlank()) {
            return identity.username();
        }
        if (identity.userId() == null) {
            return null;
        }
        return jdbcClient.sql("SELECT username FROM system_user WHERE user_id = :userId")
                .param("userId", identity.userId())
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "cannot serialize rbac audit", ex);
        }
    }

    private boolean roleExists(String roleCode) {
        return jdbcClient.sql("SELECT COUNT(*) FROM system_role WHERE role_code = :roleCode")
                .param("roleCode", roleCode)
                .query(Long.class)
                .single() > 0;
    }

    /**
     * system_dept / system_menu / system_post 的主键都不是自增列，一直靠手工分配。
     * 这里沿用同一约定按 MAX+1 取号；管理台是低并发场景，且整个保存动作在同一事务里。
     */
    private long nextId(String table, String idColumn) {
        return jdbcClient.sql("SELECT COALESCE(MAX(%s), 0) + 1 FROM %s".formatted(idColumn, table))
                .query(Long.class)
                .single();
    }

    private long lastInsertId() {
        return jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    private List<String> nullToEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String normalizeDataScope(String value) {
        if (value == null || value.isBlank()) {
            return "SELF";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("ALL", "CLINIC", "SELF", "NONE").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported data_scope");
        }
        return normalized;
    }

    private String normalizeRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
