package com.yuri.aiorder.rbac;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RbacRoleResponse(
        @JsonProperty("role_id") long roleId,
        @JsonProperty("role_code") String roleCode,
        @JsonProperty("role_name") String roleName,
        @JsonProperty("data_scope") String dataScope,
        /** 授权等级，数字越小权限越高。授权人只能授予等级严格低于自己的角色。 */
        @JsonProperty("role_level") int roleLevel,
        String status,
        String remark,
        @JsonProperty("permission_count") int permissionCount,
        @JsonProperty("menu_count") int menuCount,
        @JsonProperty("user_count") int userCount) {
}
