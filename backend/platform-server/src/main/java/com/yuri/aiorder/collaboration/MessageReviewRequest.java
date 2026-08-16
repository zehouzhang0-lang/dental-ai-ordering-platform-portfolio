package com.yuri.aiorder.collaboration;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MessageReviewRequest(
        String action,
        @JsonProperty("edited_content") String editedContent,
        @JsonProperty("review_note") String reviewNote) {
}
