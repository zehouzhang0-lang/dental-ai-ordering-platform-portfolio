package com.yuri.aiorder.quality;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ExternalReturnQualityRecordRequest(
        @JsonProperty("order_id") Long orderId,
        @JsonProperty("reason_category") String reasonCategory,
        @JsonProperty("responsibility_type") String responsibilityType,
        @JsonProperty("reason_detail") String reasonDetail) {}
