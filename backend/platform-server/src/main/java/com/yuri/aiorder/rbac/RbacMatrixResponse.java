package com.yuri.aiorder.rbac;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record RbacMatrixResponse(
        List<RbacRoleResponse> roles,
        List<RbacPermissionResponse> permissions,
        @JsonProperty("permissions_by_role") Map<String, List<String>> permissionsByRole,
        @JsonProperty("menus_by_role") Map<String, List<String>> menusByRole) {
}
