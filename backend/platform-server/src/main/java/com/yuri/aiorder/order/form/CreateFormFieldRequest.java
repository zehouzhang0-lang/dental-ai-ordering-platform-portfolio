package com.yuri.aiorder.order.form;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record CreateFormFieldRequest(
        @JsonProperty("product_type") @NotBlank String productType,
        @JsonProperty("field_key") @NotBlank String fieldKey,
        @JsonProperty("field_label") @NotBlank String fieldLabel,
        @JsonProperty("field_type") @NotBlank String fieldType,
        @JsonProperty("is_required") Boolean required,
        List<String> options,
        @JsonProperty("sort_order") Integer sortOrder) {
}
