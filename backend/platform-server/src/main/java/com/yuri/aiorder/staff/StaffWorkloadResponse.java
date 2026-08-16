package com.yuri.aiorder.staff;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.LocalDateTime;
import java.util.List;

public record StaffWorkloadResponse(
        @JsonProperty("user_id") @JsonSerialize(using = ToStringSerializer.class) long userId,
        String username,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("user_type") String userType,
        String status,
        @JsonProperty("dept_id") Long deptId,
        @JsonProperty("dept_name") String deptName,
        @JsonProperty("post_names") List<String> postNames,
        @JsonProperty("role_codes") List<String> roleCodes,
        @JsonProperty("permission_codes") List<String> permissionCodes,
        @JsonProperty("assigned_node_count") long assignedNodeCount,
        @JsonProperty("active_node_count") long activeNodeCount,
        @JsonProperty("completed_work_log_count") long completedWorkLogCount,
        @JsonProperty("effective_duration") long effectiveDuration,
        @JsonProperty("rework_count") long reworkCount,
        @JsonProperty("last_work_finished_at") LocalDateTime lastWorkFinishedAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt) {}
