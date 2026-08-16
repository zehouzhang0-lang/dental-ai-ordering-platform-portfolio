package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ReworkDictionaryItemResponse(
        @JsonProperty("item_id") long itemId,
        @JsonProperty("dictionary_type") String dictionaryType,
        String code,
        String label,
        @JsonProperty("sort_order") int sortOrder,
        String status) {
}
