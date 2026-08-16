package com.yuri.aiorder.staff;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.AccessControlService;
import com.yuri.aiorder.order.api.OrderProjectionQueryService.OrderListResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StaffWorkloadService {

    private final JdbcClient jdbcClient;
    private final AccessControlService accessControlService;

    public StaffWorkloadService(JdbcClient jdbcClient, AccessControlService accessControlService) {
        this.jdbcClient = jdbcClient;
        this.accessControlService = accessControlService;
    }

    public OrderListResponse<StaffWorkloadResponse> listStaffWorkload(
            BootstrapIdentity identity, String keyword, int page, int size) {
        requireStaffAccess(identity);
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(size, 100));
        int offset = (safePage - 1) * safeSize;
        String whereClause = staffWhereClause(identity, keyword);

        JdbcClient.StatementSpec listSpec = bindStaffParams(jdbcClient.sql("""
                        %s
                        %s
                        ORDER BY u.updated_at DESC, u.user_id DESC
                        LIMIT :limit OFFSET :offset
                        """.formatted(baseStaffSelect(), whereClause)), identity, keyword)
                .param("limit", safeSize)
                .param("offset", offset);
        List<StaffWorkloadResponse> rows = listSpec.query(this::mapStaff).list();
        long total = bindStaffParams(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM system_user u
                        LEFT JOIN system_dept d ON d.dept_id = u.dept_id
                        %s
                        """.formatted(whereClause)), identity, keyword)
                .query(Long.class)
                .single();
        return new OrderListResponse<>(rows, total, safePage, safeSize);
    }

    private void requireStaffAccess(BootstrapIdentity identity) {
        if (identity.role() == UserRole.DOCTOR) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "staff workload is internal only");
        }
        if (identity.role() == UserRole.WORKER) {
            accessControlService.requireScopedIdentity(identity, "SELF");
            return;
        }
        accessControlService.requirePermission(
                identity, "staff:read-workload", "staff workload requires staff:read-workload");
    }

    private String staffWhereClause(BootstrapIdentity identity, String keyword) {
        String where = "WHERE u.user_type IN ('ADMIN', 'CS', 'WORKER') AND u.status <> 'DELETED'";
        if (identity.role() == UserRole.WORKER) {
            where += " AND u.user_id = :selfUserId";
        }
        if (keyword != null && !keyword.isBlank()) {
            where += """
                     AND (
                        u.username LIKE :keyword
                        OR u.display_name LIKE :keyword
                        OR d.dept_name LIKE :keyword
                        OR EXISTS (
                            SELECT 1
                            FROM system_user_post up_kw
                            JOIN system_post p_kw ON p_kw.post_id = up_kw.post_id
                            WHERE up_kw.user_id = u.user_id
                              AND p_kw.post_name LIKE :keyword
                        )
                     )
                    """;
        }
        return where;
    }

    private JdbcClient.StatementSpec bindStaffParams(
            JdbcClient.StatementSpec spec, BootstrapIdentity identity, String keyword) {
        if (identity.role() == UserRole.WORKER) {
            spec = spec.param("selfUserId", identity.userId());
        }
        if (keyword != null && !keyword.isBlank()) {
            spec = spec.param("keyword", "%" + keyword.trim() + "%");
        }
        return spec;
    }

    private String baseStaffSelect() {
        return """
                SELECT
                    u.user_id,
                    u.username,
                    u.display_name,
                    u.user_type,
                    u.status,
                    u.dept_id,
                    d.dept_name,
                    COALESCE((
                        SELECT GROUP_CONCAT(p.post_name ORDER BY p.sort_order SEPARATOR ',')
                        FROM system_user_post up
                        JOIN system_post p ON p.post_id = up.post_id
                        WHERE up.user_id = u.user_id
                          AND p.status = 'ACTIVE'
                    ), '') AS post_names,
                    COALESCE((
                        SELECT GROUP_CONCAT(r.role_code ORDER BY r.role_code SEPARATOR ',')
                        FROM system_user_role ur
                        JOIN system_role r ON r.role_id = ur.role_id
                        WHERE ur.user_id = u.user_id
                          AND r.status = 'ACTIVE'
                    ), '') AS role_codes,
                    COALESCE((
                        SELECT GROUP_CONCAT(p.permission_code ORDER BY p.permission_code SEPARATOR ',')
                        FROM system_user_permission up
                        JOIN system_permission p ON p.permission_id = up.permission_id
                        WHERE up.user_id = u.user_id
                          AND p.status = 'ACTIVE'
                          AND p.permission_code = 'design-draft:internal-review'
                    ), '') AS permission_codes,
                    (
                        SELECT COUNT(*)
                        FROM order_process_node n
                        WHERE n.assigned_user_id = u.user_id
                    ) AS assigned_node_count,
                    (
                        SELECT COUNT(*)
                        FROM order_process_node n
                        WHERE n.assigned_user_id = u.user_id
                          AND n.node_status IN ('READY', 'IN_PROGRESS')
                    ) AS active_node_count,
                    (
                        SELECT COUNT(*)
                        FROM work_log w
                        WHERE w.worker_user_id = u.user_id
                          AND w.status = 'COMPLETED'
                    ) AS completed_work_log_count,
                    (
                        SELECT COALESCE(SUM(w.effective_duration_seconds), 0)
                        FROM work_log w
                        WHERE w.worker_user_id = u.user_id
                          AND w.status = 'COMPLETED'
                    ) AS effective_duration_seconds,
                    (
                        SELECT COUNT(*)
                        FROM rework_record r
                        JOIN order_process_node n ON n.node_instance_id = r.target_node_instance_id
                        WHERE n.assigned_user_id = u.user_id
                    ) AS rework_count,
                    (
                        SELECT MAX(w.finished_at)
                        FROM work_log w
                        WHERE w.worker_user_id = u.user_id
                          AND w.status = 'COMPLETED'
                    ) AS last_work_finished_at,
                    u.updated_at
                FROM system_user u
                LEFT JOIN system_dept d ON d.dept_id = u.dept_id
                """;
    }

    private StaffWorkloadResponse mapStaff(ResultSet rs, int rowNum) throws SQLException {
        return new StaffWorkloadResponse(
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("user_type"),
                rs.getString("status"),
                rs.getObject("dept_id", Long.class),
                rs.getString("dept_name"),
                splitCsv(rs.getString("post_names")),
                splitCsv(rs.getString("role_codes")),
                splitCsv(rs.getString("permission_codes")),
                rs.getLong("assigned_node_count"),
                rs.getLong("active_node_count"),
                rs.getLong("completed_work_log_count"),
                rs.getLong("effective_duration_seconds") / 60,
                rs.getLong("rework_count"),
                rs.getObject("last_work_finished_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }
}
