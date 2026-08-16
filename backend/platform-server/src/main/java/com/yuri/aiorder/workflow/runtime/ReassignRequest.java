package com.yuri.aiorder.workflow.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ReassignRequest(
        @JsonProperty("new_user_id") @Positive long newUserId,
        @NotBlank @Size(max = 500) String reason) {
}
