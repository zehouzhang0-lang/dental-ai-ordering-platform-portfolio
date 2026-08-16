package com.yuri.aiorder.collaboration;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LogisticsRequest(
        String carrier,
        @JsonProperty("tracking_no") String trackingNo) {
}
