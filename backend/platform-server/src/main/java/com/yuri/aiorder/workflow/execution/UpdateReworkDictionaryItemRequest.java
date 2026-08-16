package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UpdateReworkDictionaryItemRequest(
        String label,
        @JsonProperty("sort_order") Integer sortOrder,
        String status) {
}
