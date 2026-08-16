package com.yuri.aiorder.staff;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record StaffAccountUpdateRequest(
        @JsonProperty("display_name") String displayName,
        @JsonProperty("dept_id") Long deptId,
        @JsonProperty("post_id") Long postId,
        String status,
        @JsonProperty("new_password") String newPassword,
        @JsonProperty("permission_codes") List<String> permissionCodes) {
}
