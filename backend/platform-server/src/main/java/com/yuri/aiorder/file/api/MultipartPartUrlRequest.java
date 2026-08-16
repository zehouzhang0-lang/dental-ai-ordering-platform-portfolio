package com.yuri.aiorder.file.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record MultipartPartUrlRequest(
        @JsonProperty("upload_id") @NotBlank String uploadId,
        @JsonProperty("part_number") @NotNull @Positive Integer partNumber) {
}
