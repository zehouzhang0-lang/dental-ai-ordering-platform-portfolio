package com.yuri.aiorder.file.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UploadTokenResponse(
        @JsonProperty("file_id") long fileId,
        @JsonProperty("upload_url") String uploadUrl,
        @JsonProperty("expires_in_seconds") int expiresInSeconds) {
}
