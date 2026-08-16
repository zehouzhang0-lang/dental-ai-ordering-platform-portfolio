package com.yuri.aiorder.workflow.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record AssignmentRequest(@NotEmpty List<@Valid AssignmentItem> assignments) {

    public record AssignmentItem(
            @JsonProperty("node_instance_id") @Positive long nodeInstanceId,
            @JsonProperty("user_id") @Positive long userId) {
    }
}
