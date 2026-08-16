package com.yuri.aiorder.file.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record UploadTokenRequest(
        @JsonProperty("order_id") @NotNull Long orderId,
        @JsonProperty("source_type") @NotBlank String sourceType,
        @NotBlank String visibility,
        @JsonProperty("original_filename") @NotBlank String originalFilename,
        @JsonProperty("content_type") @NotBlank String contentType,
        @JsonProperty("file_size") @NotNull @Positive Long fileSize) {
}
