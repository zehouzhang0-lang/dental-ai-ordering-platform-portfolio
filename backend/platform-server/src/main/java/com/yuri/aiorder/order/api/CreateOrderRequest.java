package com.yuri.aiorder.order.api;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CreateOrderRequest(
        @JsonProperty("patient_id") Long patientId,
        @JsonProperty("product_type") @NotBlank String productType,
        @JsonProperty("form_data") @NotNull JsonNode formData,
        @JsonProperty("file_ids") List<Long> fileIds,
        @JsonProperty("is_draft") @JsonAlias("draft") Boolean draft) {
}
