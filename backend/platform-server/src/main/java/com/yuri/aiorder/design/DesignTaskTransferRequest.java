package com.yuri.aiorder.design;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DesignTaskTransferRequest(
        @JsonProperty("new_user_id") Long newUserId,
        String reason) {
}
