package com.yuri.aiorder.rbac;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RbacPostResponse(
        @JsonProperty("post_id") long postId,
        @JsonProperty("post_code") String postCode,
        @JsonProperty("post_name") String postName,
        @JsonProperty("sort_order") int sortOrder,
        String status) {
}
