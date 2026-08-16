package com.yuri.aiorder.file.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FileCompleteResponse(
        @JsonProperty("file_id") long fileId,
        @JsonProperty("upload_status") String uploadStatus,
        @JsonProperty("file_size") long fileSize,
        @JsonProperty("content_type") String contentType,
        String checksum) {
}
