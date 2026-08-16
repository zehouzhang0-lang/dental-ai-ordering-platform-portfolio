package com.yuri.aiorder.rbac;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RbacRoleRequest(
        @JsonProperty("role_code") String roleCode,
        @JsonProperty("role_name") String roleName,
        @JsonProperty("data_scope") String dataScope,
        @JsonProperty("role_level") Integer roleLevel,
        String remark,
        String reason) {
}
