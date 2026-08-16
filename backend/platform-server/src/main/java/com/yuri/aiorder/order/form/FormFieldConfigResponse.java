package com.yuri.aiorder.order.form;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record FormFieldConfigResponse(
        @JsonProperty("field_id") long fieldId,
        @JsonProperty("product_type") String productType,
        @JsonProperty("field_key") String fieldKey,
        @JsonProperty("field_label") String fieldLabel,
        @JsonProperty("field_type") String fieldType,
        @JsonProperty("is_required") boolean required,
        List<String> options,
        @JsonProperty("sort_order") int sortOrder,
        String status) {
}
