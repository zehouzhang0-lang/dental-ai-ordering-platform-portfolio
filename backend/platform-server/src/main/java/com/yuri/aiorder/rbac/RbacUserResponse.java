package com.yuri.aiorder.rbac;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record RbacUserResponse(
        @JsonProperty("user_id") long userId,
        String username,
        @JsonProperty("display_name") String displayName,
        String status,
        @JsonProperty("dept_id") Long deptId,
        @JsonProperty("dept_name") String deptName,
        @JsonProperty("data_scope") String dataScope,
        @JsonProperty("role_codes") List<String> roleCodes,
        @JsonProperty("post_codes") List<String> postCodes) {
}
