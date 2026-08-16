package com.yuri.aiorder.file.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileSignedUrlResponse(
        @JsonProperty("file_id") long fileId,
        @JsonProperty("preview_url") String previewUrl,
        @JsonProperty("download_url") String downloadUrl,
        @JsonProperty("expires_in_seconds") int expiresInSeconds) {
}
