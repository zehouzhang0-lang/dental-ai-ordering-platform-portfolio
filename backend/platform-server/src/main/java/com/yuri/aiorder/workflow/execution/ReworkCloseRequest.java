package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ReworkCloseRequest(
        @JsonProperty("reason_category") String reasonCategory,
        @JsonProperty("responsibility_type") String responsibilityType,
        @JsonProperty("close_note") String closeNote) {
}
