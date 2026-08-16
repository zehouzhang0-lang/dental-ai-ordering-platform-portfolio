package com.yuri.aiorder.order.form;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record UpdateFormFieldRequest(
        @JsonProperty("field_label") String fieldLabel,
        @JsonProperty("is_required") Boolean required,
        List<String> options,
        @JsonProperty("sort_order") Integer sortOrder,
        String status) {
}
