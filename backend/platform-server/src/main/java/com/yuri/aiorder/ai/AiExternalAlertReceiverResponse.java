package com.yuri.aiorder.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AiExternalAlertReceiverResponse(
        boolean accepted,
        @JsonProperty("event_type") String eventType,
        String nonce) {
}
