package com.yuri.aiorder.collaboration;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record DesignDraftRequest(
        @JsonProperty("file_ids") List<Long> fileIds,
        @JsonProperty("upload_note") String uploadNote,
        @JsonProperty("submission_key") String submissionKey) {
}
