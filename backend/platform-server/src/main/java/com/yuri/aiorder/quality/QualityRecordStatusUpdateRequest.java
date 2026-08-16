package com.yuri.aiorder.quality;

import com.fasterxml.jackson.annotation.JsonProperty;

public record QualityRecordStatusUpdateRequest(
        String status,
        @JsonProperty("status_note") String statusNote) {}
