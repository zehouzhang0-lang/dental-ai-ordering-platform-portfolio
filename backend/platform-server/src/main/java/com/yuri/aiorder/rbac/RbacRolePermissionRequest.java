package com.yuri.aiorder.rbac;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record RbacRolePermissionRequest(
        @JsonProperty("permission_codes") List<String> permissionCodes,
        @JsonProperty("menu_codes") List<String> menuCodes,
        @JsonProperty("data_scope") String dataScope,
        String reason) {
}
