package com.yuri.aiorder.common.auth;

import com.yuri.aiorder.common.UserRole;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 按入口角色（Portal）解析该角色当前配置的权限码与数据范围。
 *
 * <p>用于 bootstrap header 身份：这类身份此前的权限集合恒为空，只能靠
 * {@code app.auth.allow-role-fallback} 的角色兜底才能访问接口——这正是「新增细分角色拿不到权限」
 * 和「删掉权限码仍然能访问」的根因。让它带上入口角色实际配置的权限码后，
 * 服务层就可以统一改成纯权限码判定，删权限码会真正产生 403。
 *
 * <p>不做缓存：bootstrap header 只用于本地开发、演示和测试链路，真实登录走
 * {@link DatabaseAuthService} 的按用户查询；而权限配置在管理端随时可改，
 * 缓存会让「改完配置立即生效」这条验收要求变得不可靠。
 */
@Component
public class RolePermissionCatalog {

    private final JdbcClient jdbcClient;

    public RolePermissionCatalog(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public RoleAuthorization forRole(UserRole role) {
        if (role == null) {
            return RoleAuthorization.empty();
        }
        List<String> permissions = jdbcClient.sql("""
                        SELECT p.permission_code
                        FROM system_role r
                        JOIN system_role_permission rp ON rp.role_id = r.role_id
                        JOIN system_permission p ON p.permission_id = rp.permission_id
                        WHERE r.role_code = :roleCode
                          AND r.status = 'ACTIVE'
                          AND p.status = 'ACTIVE'
                        """)
                .param("roleCode", role.name())
                .query(String.class)
                .list();
        String dataScope = jdbcClient.sql("""
                        SELECT data_scope
                        FROM system_role
                        WHERE role_code = :roleCode
                          AND status = 'ACTIVE'
                        """)
                .param("roleCode", role.name())
                .query(String.class)
                .optional()
                .orElse(null);
        return new RoleAuthorization(Set.copyOf(permissions), dataScope);
    }

    public record RoleAuthorization(Set<String> permissions, String dataScope) {

        public static RoleAuthorization empty() {
            return new RoleAuthorization(Set.of(), null);
        }
    }
}
