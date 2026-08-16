package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateReworkDictionaryItemRequest(
        @JsonProperty("dictionary_type") String dictionaryType,
        String code,
        String label,
        @JsonProperty("sort_order") Integer sortOrder) {
}
