package com.yuri.aiorder.file.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record MultipartPendingUploadsResponse(
        List<Item> items) {

    public record Item(
            @JsonProperty("file_id") long fileId,
            @JsonProperty("upload_id") String uploadId,
            @JsonProperty("order_id") long orderId,
            @JsonProperty("source_type") String sourceType,
            String visibility,
            @JsonProperty("original_filename") String originalFilename,
            @JsonProperty("content_type") String contentType,
            @JsonProperty("file_size") long fileSize,
            @JsonProperty("part_size") long partSize,
            @JsonProperty("part_count") int partCount) {
    }
}
