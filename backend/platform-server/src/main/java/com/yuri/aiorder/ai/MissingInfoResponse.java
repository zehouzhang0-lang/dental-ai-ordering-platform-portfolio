package com.yuri.aiorder.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record MissingInfoResponse(
        @JsonProperty("is_complete") boolean complete,
        @JsonProperty("missing_items") List<MissingItem> missingItems) {

    public record MissingItem(
            @JsonProperty("field_key") String fieldKey,
            @JsonProperty("field_label") String fieldLabel,
            String tip) {
    }
}
