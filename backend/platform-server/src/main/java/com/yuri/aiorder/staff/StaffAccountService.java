package com.yuri.aiorder.staff;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.PasswordHashService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StaffAccountService {

    private static final Set<String> ASSIGNABLE_DIRECT_PERMISSION_CODES =
            Set.of(
                    "design-draft:internal-review",
                    "workflow:review-production",
                    "final-inspection:manage");

    private final JdbcClient jdbcClient;
    private final PasswordHashService passwordHashService;

    public StaffAccountService(JdbcClient jdbcClient, PasswordHashService passwordHashService) {
        this.jdbcClient = jdbcClient;
        this.passwordHashService = passwordHashService;
    }

    @Transactional
    public StaffAccountResponse createWorker(StaffAccountCreateRequest request, BootstrapIdentity identity) {
        requireAdmin(identity);
        String username = requiredText(request.username(), "username is required");
        String password = requiredText(request.initialPassword(), "initial_password is required");
        if (password.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "initial_password must be at least 8 characters");
        }
        String displayName = requiredText(request.displayName(), "display_name is required");
        requireActiveDepartment(request.deptId());
        requireActivePost(request.postId());
        long userId = jdbcClient.sql("SELECT UUID_SHORT()").query(Long.class).single();
        try {
            jdbcClient.sql("""
                            INSERT INTO system_user
                                (user_id, username, password_hash, display_name, dept_id, user_type, status)
                            VALUES
                                (:userId, :username, :passwordHash, :displayName, :deptId, 'WORKER', 'ACTIVE')
                            """)
                    .param("userId", userId)
                    .param("username", username)
                    .param("passwordHash", passwordHashService.hash(password))
                    .param("displayName", displayName)
                    .param("deptId", request.deptId())
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "username already exists", ex);
        }
        jdbcClient.sql("""
                        INSERT INTO system_user_role (user_id, role_id)
                        SELECT :userId, role_id
                        FROM system_role
                        WHERE role_code = 'WORKER'
                          AND status = 'ACTIVE'
                        """)
                .param("userId", userId)
                .update();
        jdbcClient.sql("INSERT INTO system_user_post (user_id, post_id) VALUES (:userId, :postId)")
                .param("userId", userId)
                .param("postId", request.postId())
                .update();
        replaceDirectPermissions(userId, request.permissionCodes());
        return loadWorker(userId);
    }

    @Transactional
    public StaffAccountResponse updateWorker(long userId, StaffAccountUpdateRequest request, BootstrapIdentity identity) {
        requireAdmin(identity);
        StaffAccountResponse current = loadWorker(userId);
        String displayName = request.displayName() == null ? current.displayName()
                : requiredText(request.displayName(), "display_name is required");
        long deptId = request.deptId() == null ? current.deptId() : request.deptId();
        long postId = request.postId() == null ? current.postId() : request.postId();
        requireActiveDepartment(deptId);
        requireActivePost(postId);
        String status = request.status() == null ? current.status() : normalizeStatus(request.status());
        if ("ACTIVE".equals(current.status()) && !"ACTIVE".equals(status)) {
            requireNoActiveDesignTasks(userId);
        }
        String passwordHash = null;
        if (request.newPassword() != null) {
            String newPassword = requiredText(request.newPassword(), "new_password is required");
            if (newPassword.length() < 8) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "new_password must be at least 8 characters");
            }
            passwordHash = passwordHashService.hash(newPassword);
        }
        jdbcClient.sql("""
                        UPDATE system_user
                        SET display_name = :displayName,
                            dept_id = :deptId,
                            status = :status,
                            password_hash = COALESCE(:passwordHash, password_hash)
                        WHERE user_id = :userId
                          AND user_type = 'WORKER'
                        """)
                .param("displayName", displayName)
                .param("deptId", deptId)
                .param("status", status)
                .param("passwordHash", passwordHash)
                .param("userId", userId)
                .update();
        jdbcClient.sql("DELETE FROM system_user_post WHERE user_id = :userId")
                .param("userId", userId)
                .update();
        jdbcClient.sql("INSERT INTO system_user_post (user_id, post_id) VALUES (:userId, :postId)")
                .param("userId", userId)
                .param("postId", postId)
                .update();
        if (request.permissionCodes() != null) {
            replaceDirectPermissions(userId, request.permissionCodes());
        }
        return loadWorker(userId);
    }

    private void requireNoActiveDesignTasks(long userId) {
        long activeTaskCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM design_task
                        WHERE assigned_user_id = :userId
                          AND task_status NOT IN ('DOCTOR_CONFIRMED', 'CANCELLED')
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single();
        if (activeTaskCount > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "worker still has active design tasks; transfer them before disabling the account");
        }
    }

    public StaffAccountOptionsResponse getAccountOptions(BootstrapIdentity identity) {
        requireAdmin(identity);
        return new StaffAccountOptionsResponse(
                jdbcClient.sql("SELECT dept_id, dept_name FROM system_dept WHERE status = 'ACTIVE' ORDER BY sort_order, dept_id")
                        .query((rs, rowNum) -> new StaffAccountOptionsResponse.Option(
                                rs.getLong("dept_id"), rs.getString("dept_name")))
                        .list(),
                jdbcClient.sql("SELECT post_id, post_name FROM system_post WHERE status = 'ACTIVE' ORDER BY sort_order, post_id")
                        .query((rs, rowNum) -> new StaffAccountOptionsResponse.Option(
                                rs.getLong("post_id"), rs.getString("post_name")))
                        .list(),
                jdbcClient.sql("""
                                SELECT permission_code, permission_name
                                FROM system_permission
                                WHERE status = 'ACTIVE'
                                  AND permission_code IN (
                                      'design-draft:internal-review',
                                      'workflow:review-production',
                                      'final-inspection:manage'
                                  )
                                ORDER BY permission_code
                                """)
                        .query((rs, rowNum) -> new StaffAccountOptionsResponse.PermissionOption(
                                rs.getString("permission_code"), rs.getString("permission_name")))
                        .list());
    }

    private StaffAccountResponse loadWorker(long userId) {
        return jdbcClient.sql("""
                        SELECT u.user_id, u.username, u.display_name, u.dept_id, d.dept_name,
                               p.post_id, p.post_name, u.status,
                               COALESCE((
                                   SELECT GROUP_CONCAT(direct_permission.permission_code
                                       ORDER BY direct_permission.permission_code SEPARATOR ',')
                                   FROM system_user_permission user_permission
                                   JOIN system_permission direct_permission
                                     ON direct_permission.permission_id = user_permission.permission_id
                                   WHERE user_permission.user_id = u.user_id
                                     AND direct_permission.status = 'ACTIVE'
                                     AND direct_permission.permission_code IN (
                                         'design-draft:internal-review',
                                         'workflow:review-production',
                                         'final-inspection:manage'
                                     )
                               ), '') AS permission_codes
                        FROM system_user u
                        JOIN system_dept d ON d.dept_id = u.dept_id
                        JOIN system_user_post up ON up.user_id = u.user_id
                        JOIN system_post p ON p.post_id = up.post_id
                        WHERE u.user_id = :userId
                          AND u.user_type = 'WORKER'
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> new StaffAccountResponse(
                        rs.getLong("user_id"),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getLong("dept_id"),
                        rs.getString("dept_name"),
                        rs.getLong("post_id"),
                        rs.getString("post_name"),
                        "WORKER",
                        rs.getString("status"),
                        splitPermissionCodes(rs.getString("permission_codes"))))
                .single();
    }

    private void replaceDirectPermissions(long userId, List<String> requestedCodes) {
        List<String> permissionCodes = normalizeDirectPermissionCodes(requestedCodes);
        jdbcClient.sql("""
                        DELETE FROM system_user_permission
                        WHERE user_id = :userId
                          AND permission_id IN (
                              SELECT permission_id
                              FROM system_permission
                              WHERE permission_code IN (
                                  'design-draft:internal-review',
                                  'workflow:review-production',
                                  'final-inspection:manage'
                              )
                          )
                        """)
                .param("userId", userId)
                .update();
        for (String permissionCode : permissionCodes) {
            int inserted = jdbcClient.sql("""
                            INSERT INTO system_user_permission (user_id, permission_id)
                            SELECT :userId, permission_id
                            FROM system_permission
                            WHERE permission_code = :permissionCode
                              AND status = 'ACTIVE'
                            """)
                    .param("userId", userId)
                    .param("permissionCode", permissionCode)
                    .update();
            if (inserted != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "permission_code is not active");
            }
        }
    }

    private List<String> normalizeDirectPermissionCodes(List<String> requestedCodes) {
        if (requestedCodes == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String requestedCode : requestedCodes) {
            String permissionCode = requiredText(requestedCode, "permission_code is required");
            if (!ASSIGNABLE_DIRECT_PERMISSION_CODES.contains(permissionCode)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "permission_code is not assignable");
            }
            normalized.add(permissionCode);
        }
        return List.copyOf(normalized);
    }

    private List<String> splitPermissionCodes(String permissionCodes) {
        if (permissionCodes == null || permissionCodes.isBlank()) {
            return List.of();
        }
        return List.of(permissionCodes.split(","));
    }

    private void requireActiveDepartment(Long deptId) {
        if (deptId == null || jdbcClient.sql("SELECT COUNT(*) FROM system_dept WHERE dept_id = :deptId AND status = 'ACTIVE'")
                .param("deptId", deptId)
                .query(Long.class)
                .single() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "active dept_id is required");
        }
    }

    private void requireActivePost(Long postId) {
        if (postId == null || jdbcClient.sql("SELECT COUNT(*) FROM system_post WHERE post_id = :postId AND status = 'ACTIVE'")
                .param("postId", postId)
                .query(Long.class)
                .single() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "active post_id is required");
        }
    }

    private void requireAdmin(BootstrapIdentity identity) {
        if (identity.role() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "staff account management requires ADMIN role");
        }
    }

    private String requiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private String normalizeStatus(String value) {
        String normalized = requiredText(value, "status is required").toUpperCase();
        if (!"ACTIVE".equals(normalized) && !"DISABLED".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be ACTIVE or DISABLED");
        }
        return normalized;
    }
}
