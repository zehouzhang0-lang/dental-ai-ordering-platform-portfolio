package com.yuri.aiorder.file.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record OrderFileResponse(
        @JsonProperty("file_id") long fileId,
        @JsonProperty("source_type") String sourceType,
        String visibility,
        @JsonProperty("original_filename") String originalFilename,
        @JsonProperty("content_type") String contentType,
        @JsonProperty("file_size") Long fileSize,
        @JsonProperty("upload_status") String uploadStatus,
        @JsonProperty("created_at") LocalDateTime createdAt) {
}
