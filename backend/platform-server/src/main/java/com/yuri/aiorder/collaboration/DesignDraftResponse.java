package com.yuri.aiorder.collaboration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.LocalDateTime;
import java.util.List;

public record DesignDraftResponse(
        @JsonProperty("draft_id") long draftId,
        @JsonProperty("order_id") long orderId,
        int version,
        @JsonProperty("uploader_user_id")
        @JsonSerialize(using = ToStringSerializer.class)
        Long uploaderUserId,
        @JsonProperty("file_id") Long fileId,
        @JsonProperty("file_ids") List<Long> fileIds,
        @JsonProperty("file_count") int fileCount,
        String status,
        @JsonProperty("upload_note") String uploadNote,
        @JsonProperty("submission_key") String submissionKey,
        @JsonProperty("submitted_at") LocalDateTime submittedAt,
        @JsonProperty("doctor_visible_at") LocalDateTime doctorVisibleAt,
        @JsonProperty("internal_reject_reason") String internalRejectReason,
        @JsonProperty("cs_reject_reason") String csRejectReason,
        @JsonProperty("doctor_reject_reason") String doctorRejectReason,
        @JsonProperty("review_history") List<DesignDraftReviewEventResponse> reviewHistory) {
}
