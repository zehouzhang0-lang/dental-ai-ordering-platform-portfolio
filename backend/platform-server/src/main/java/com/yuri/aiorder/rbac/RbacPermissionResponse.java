package com.yuri.aiorder.rbac;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RbacPermissionResponse(
        @JsonProperty("permission_id") long permissionId,
        @JsonProperty("permission_code") String permissionCode,
        @JsonProperty("permission_name") String permissionName,
        @JsonProperty("module_code") String moduleCode,
        String status) {
}
