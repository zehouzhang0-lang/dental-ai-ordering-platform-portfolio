package com.yuri.aiorder.rbac;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RbacDeptResponse(
        @JsonProperty("dept_id") long deptId,
        @JsonProperty("parent_id") Long parentId,
        @JsonProperty("dept_code") String deptCode,
        @JsonProperty("dept_name") String deptName,
        @JsonProperty("sort_order") int sortOrder,
        String status,
        @JsonProperty("member_count") int memberCount) {
}
