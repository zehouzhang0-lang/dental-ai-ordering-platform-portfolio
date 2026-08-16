package com.yuri.aiorder.rbac;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RbacPostRequest(
        @JsonProperty("post_code") String postCode,
        @JsonProperty("post_name") String postName,
        @JsonProperty("sort_order") Integer sortOrder,
        String status,
        String reason) {
}
