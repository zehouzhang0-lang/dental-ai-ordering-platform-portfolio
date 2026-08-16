package com.yuri.aiorder.rbac;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.DataResponse;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.RequirePermission;
import java.util.List;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端角色 / 权限 / 组织管理接口（TASK-034 C 批次，关闭客户 CHK064-066）。
 *
 * <p>注解只做粗筛，真正的授权边界（等级、跨部门、不能授予自己没有的权限码）在
 * {@link RbacAdminService} 中按权限码与数据判定。
 */
@RestController
@CrossOrigin(origins = "${app.cors.allowed-origin:http://localhost:5173}")
public class RbacAdminController {

    private final RbacAdminService rbacAdminService;

    public RbacAdminController(RbacAdminService rbacAdminService) {
        this.rbacAdminService = rbacAdminService;
    }

    @GetMapping("/rbac/roles")
    @RequirePermission(value = {"rbac:matrix:read", "rbac:role:manage"}, roles = UserRole.ADMIN)
    public DataResponse<List<RbacRoleResponse>> listRoles(BootstrapIdentity identity) {
        return new DataResponse<>(rbacAdminService.listRoles(identity));
    }

    @PostMapping("/rbac/roles")
    @RequirePermission(value = "rbac:role:manage", roles = UserRole.ADMIN)
    public DataResponse<RbacRoleResponse> createRole(
            @RequestBody RbacRoleRequest request, BootstrapIdentity identity) {
        return new DataResponse<>(rbacAdminService.createRole(request, identity));
    }

    @PutMapping("/rbac/roles/{roleId}")
    @RequirePermission(value = "rbac:role:manage", roles = UserRole.ADMIN)
    public DataResponse<RbacRoleResponse> updateRole(
            @PathVariable long roleId, @RequestBody RbacRoleRequest request, BootstrapIdentity identity) {
        return new DataResponse<>(rbacAdminService.updateRole(roleId, request, identity));
    }

    @PutMapping("/rbac/roles/{roleId}/status")
    @RequirePermission(value = "rbac:role:manage", roles = UserRole.ADMIN)
    public DataResponse<RbacRoleResponse> updateRoleStatus(
            @PathVariable long roleId, @RequestBody RbacStatusRequest request, BootstrapIdentity identity) {
        return new DataResponse<>(rbacAdminService.updateRoleStatus(roleId, request.status(), request.reason(), identity));
    }

    @GetMapping("/rbac/roles/{roleId}/permissions")
    @RequirePermission(value = {"rbac:matrix:read", "rbac:permission:assign"}, roles = UserRole.ADMIN)
    public DataResponse<RbacRolePermissionResponse> getRolePermissions(
            @PathVariable long roleId, BootstrapIdentity identity) {
        return new DataResponse<>(rbacAdminService.getRolePermissions(roleId, identity));
    }

    @PutMapping("/rbac/roles/{roleId}/permissions")
    @RequirePermission(value = "rbac:permission:assign", roles = UserRole.ADMIN)
    public DataResponse<RbacRolePermissionResponse> updateRolePermissions(
            @PathVariable long roleId,
            @RequestBody RbacRolePermissionRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(rbacAdminService.updateRolePermissions(roleId, request, identity));
    }

    @GetMapping("/rbac/permissions")
    @RequirePermission(value = {"rbac:matrix:read", "rbac:permission:assign"}, roles = UserRole.ADMIN)
    public DataResponse<List<RbacPermissionResponse>> listPermissions(BootstrapIdentity identity) {
        return new DataResponse<>(rbacAdminService.listPermissions(identity));
    }

    @GetMapping("/rbac/departments")
    @RequirePermission(value = {"rbac:matrix:read", "rbac:org:manage"}, roles = UserRole.ADMIN)
    public DataResponse<List<RbacDeptResponse>> listDepartments(BootstrapIdentity identity) {
        return new DataResponse<>(rbacAdminService.listDepartments(identity));
    }

    @PostMapping("/rbac/departments")
    @RequirePermission(value = "rbac:org:manage", roles = UserRole.ADMIN)
    public DataResponse<RbacDeptResponse> createDepartment(
            @RequestBody RbacDeptRequest request, BootstrapIdentity identity) {
        return new DataResponse<>(rbacAdminService.saveDepartment(null, request, identity));
    }

    @PutMapping("/rbac/departments/{deptId}")
    @RequirePermission(value = "rbac:org:manage", roles = UserRole.ADMIN)
    public DataResponse<RbacDeptResponse> updateDepartment(
            @PathVariable long deptId, @RequestBody RbacDeptRequest request, BootstrapIdentity identity) {
        return new DataResponse<>(rbacAdminService.saveDepartment(deptId, request, identity));
    }

    @GetMapping("/rbac/posts")
    @RequirePermission(value = {"rbac:matrix:read", "rbac:org:manage"}, roles = UserRole.ADMIN)
    public DataResponse<List<RbacPostResponse>> listPosts(BootstrapIdentity identity) {
        return new DataResponse<>(rbacAdminService.listPosts(identity));
    }

    @PostMapping("/rbac/posts")
    @RequirePermission(value = "rbac:org:manage", roles = UserRole.ADMIN)
    public DataResponse<RbacPostResponse> createPost(
            @RequestBody RbacPostRequest request, BootstrapIdentity identity) {
        return new DataResponse<>(rbacAdminService.savePost(null, request, identity));
    }

    @PutMapping("/rbac/posts/{postId}")
    @RequirePermission(value = "rbac:org:manage", roles = UserRole.ADMIN)
    public DataResponse<RbacPostResponse> updatePost(
            @PathVariable long postId, @RequestBody RbacPostRequest request, BootstrapIdentity identity) {
        return new DataResponse<>(rbacAdminService.savePost(postId, request, identity));
    }

    @GetMapping("/rbac/users")
    @RequirePermission(value = {"rbac:matrix:read", "rbac:user:assign"}, roles = UserRole.ADMIN)
    public DataResponse<List<RbacUserResponse>> listUsers(BootstrapIdentity identity) {
        return new DataResponse<>(rbacAdminService.listUsers(identity));
    }

    @PutMapping("/rbac/users/{userId}/assignment")
    @RequirePermission(value = "rbac:user:assign", roles = UserRole.ADMIN)
    public DataResponse<RbacUserResponse> assignUser(
            @PathVariable long userId, @RequestBody RbacUserAssignRequest request, BootstrapIdentity identity) {
        return new DataResponse<>(rbacAdminService.assignUser(userId, request, identity));
    }

    @PutMapping("/rbac/users/{userId}/status")
    @RequirePermission(value = "account:disable", roles = UserRole.ADMIN)
    public DataResponse<RbacUserResponse> updateUserStatus(
            @PathVariable long userId, @RequestBody RbacStatusRequest request, BootstrapIdentity identity) {
        return new DataResponse<>(rbacAdminService.updateUserStatus(userId, request.status(), request.reason(), identity));
    }

    @PostMapping("/rbac/users/{userId}/password-reset")
    @RequirePermission(value = "account:reset-password", roles = UserRole.ADMIN)
    public DataResponse<RbacPasswordResetResponse> resetPassword(
            @PathVariable long userId, @RequestBody RbacStatusRequest request, BootstrapIdentity identity) {
        return new DataResponse<>(rbacAdminService.resetPassword(userId, request.reason(), identity));
    }

    @GetMapping("/rbac/matrix")
    @RequirePermission(value = "rbac:matrix:read", roles = UserRole.ADMIN)
    public DataResponse<RbacMatrixResponse> getMatrix(BootstrapIdentity identity) {
        return new DataResponse<>(rbacAdminService.getPermissionMatrix(identity));
    }

    @GetMapping("/rbac/audits")
    @RequirePermission(value = "rbac:matrix:read", roles = UserRole.ADMIN)
    public DataResponse<List<RbacAuditResponse>> listAudits(
            @RequestParam(name = "entity_type", required = false) String entityType,
            @RequestParam(defaultValue = "50") int limit,
            BootstrapIdentity identity) {
        return new DataResponse<>(rbacAdminService.listAudits(identity, entityType, limit));
    }
}
