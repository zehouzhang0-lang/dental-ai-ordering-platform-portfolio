package com.yuri.aiorder.file.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record MultipartStatusResponse(
        @JsonProperty("file_id") long fileId,
        @JsonProperty("upload_id") String uploadId,
        @JsonProperty("upload_status") String uploadStatus,
        @JsonProperty("part_size") Long partSize,
        @JsonProperty("part_count") Integer partCount,
        @JsonProperty("completed_parts") List<PartStatus> completedParts) {

    public record PartStatus(
            @JsonProperty("part_number") int partNumber,
            String etag,
            long size) {
    }
}
