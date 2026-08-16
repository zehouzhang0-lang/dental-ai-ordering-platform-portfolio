package com.yuri.aiorder.staff;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.util.List;

public record StaffAccountResponse(
        @JsonProperty("user_id") @JsonSerialize(using = ToStringSerializer.class) long userId,
        String username,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("dept_id") long deptId,
        @JsonProperty("dept_name") String deptName,
        @JsonProperty("post_id") long postId,
        @JsonProperty("post_name") String postName,
        String role,
        String status,
        @JsonProperty("permission_codes") List<String> permissionCodes) {
}
