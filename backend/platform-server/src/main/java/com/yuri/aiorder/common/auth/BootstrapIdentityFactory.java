package com.yuri.aiorder.common.auth;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import org.springframework.stereotype.Component;

/**
 * 构造请求身份：已通过 Bearer 认证时直接复用认证身份；否则按 bootstrap header 构造，
 * 并补上该入口角色当前配置的权限码与数据范围。
 *
 * <p>补权限码这一步是 TASK-034 A 批次的前提：服务层要改成纯权限码判定，
 * 身份就必须真的带着权限码，否则所有走 bootstrap header 的链路会全部 403。
 */
@Component
public class BootstrapIdentityFactory {

    private final RolePermissionCatalog rolePermissionCatalog;

    public BootstrapIdentityFactory(RolePermissionCatalog rolePermissionCatalog) {
        this.rolePermissionCatalog = rolePermissionCatalog;
    }

    public BootstrapIdentity resolve(String roleHeader, Long userId, Long clinicId) {
        BootstrapIdentity identity = BootstrapIdentity.fromHeaders(roleHeader, userId, clinicId);
        if (!identity.permissions().isEmpty() || identity.role() == null) {
            return identity;
        }
        UserRole role = identity.role();
        RolePermissionCatalog.RoleAuthorization authorization = rolePermissionCatalog.forRole(role);
        if (authorization.permissions().isEmpty() && authorization.dataScope() == null) {
            return identity;
        }
        return new BootstrapIdentity(
                role,
                identity.userId(),
                identity.clinicId(),
                identity.username(),
                authorization.permissions(),
                identity.dataScope() != null ? identity.dataScope() : authorization.dataScope());
    }
}
