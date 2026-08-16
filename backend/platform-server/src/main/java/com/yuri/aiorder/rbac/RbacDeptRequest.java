package com.yuri.aiorder.rbac;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RbacDeptRequest(
        @JsonProperty("parent_id") Long parentId,
        @JsonProperty("dept_code") String deptCode,
        @JsonProperty("dept_name") String deptName,
        @JsonProperty("sort_order") Integer sortOrder,
        String status,
        String reason) {
}
