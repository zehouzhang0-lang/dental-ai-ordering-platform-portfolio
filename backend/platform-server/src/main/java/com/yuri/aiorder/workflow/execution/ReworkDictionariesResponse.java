package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ReworkDictionariesResponse(
        @JsonProperty("reason_categories") List<ReworkDictionaryOption> reasonCategories,
        @JsonProperty("responsibility_types") List<ReworkDictionaryOption> responsibilityTypes) {
}
