package com.yuri.aiorder.rbac;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record RbacRolePermissionResponse(
        @JsonProperty("role_id") long roleId,
        @JsonProperty("role_code") String roleCode,
        @JsonProperty("data_scope") String dataScope,
        @JsonProperty("permission_codes") List<String> permissionCodes,
        @JsonProperty("menu_codes") List<String> menuCodes) {
}
