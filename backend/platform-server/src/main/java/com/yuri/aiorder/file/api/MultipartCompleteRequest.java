package com.yuri.aiorder.file.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record MultipartCompleteRequest(
        @JsonProperty("upload_id") @NotBlank String uploadId,
        @NotEmpty List<@Valid Part> parts) {

    public record Part(
            @JsonProperty("part_number") @NotNull @Positive Integer partNumber,
            @NotBlank String etag) {
    }
}
