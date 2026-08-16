package com.yuri.aiorder.collaboration;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LogisticsExceptionRequest(
        @JsonProperty("logistics_status") String logisticsStatus,
        @JsonProperty("follow_up_note") String followUpNote) {
}
