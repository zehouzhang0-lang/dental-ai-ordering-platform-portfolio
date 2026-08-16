package com.yuri.aiorder.rbac;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record RbacUserAssignRequest(
        @JsonProperty("role_codes") List<String> roleCodes,
        @JsonProperty("post_codes") List<String> postCodes,
        @JsonProperty("dept_id") Long deptId,
        @JsonProperty("data_scope") String dataScope,
        String reason) {
}
