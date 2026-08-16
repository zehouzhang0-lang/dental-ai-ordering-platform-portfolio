package com.yuri.aiorder.collaboration;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DesignDraftReviewRequest(
        String action,
        @JsonProperty("internal_reject_reason") String internalRejectReason,
        @JsonProperty("cs_reject_reason") String csRejectReason) {

    public String resolvedRejectReason() {
        return internalRejectReason == null || internalRejectReason.isBlank()
                ? csRejectReason
                : internalRejectReason;
    }
}
