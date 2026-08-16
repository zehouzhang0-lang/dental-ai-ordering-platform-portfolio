package com.yuri.aiorder.collaboration;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MentionableUserResponse(
        @JsonProperty("user_id") long userId,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("user_role") String userRole) {
}
