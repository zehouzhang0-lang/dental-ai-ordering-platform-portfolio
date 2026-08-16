package com.yuri.aiorder.file.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MultipartInitiateResponse(
        @JsonProperty("file_id") long fileId,
        @JsonProperty("upload_id") String uploadId,
        @JsonProperty("part_size") long partSize,
        @JsonProperty("part_count") int partCount,
        @JsonProperty("expires_in_seconds") int expiresInSeconds) {
}
