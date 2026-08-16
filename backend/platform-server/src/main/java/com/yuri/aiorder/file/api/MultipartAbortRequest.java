package com.yuri.aiorder.file.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record MultipartAbortRequest(
        @JsonProperty("upload_id") @NotBlank String uploadId) {
}
