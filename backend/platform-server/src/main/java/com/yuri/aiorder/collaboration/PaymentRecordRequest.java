package com.yuri.aiorder.collaboration;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record PaymentRecordRequest(
        @JsonProperty("amount_cents") Long amountCents,
        @JsonProperty("currency") String currency,
        @JsonProperty("payment_method") String paymentMethod,
        @JsonProperty("received_at") LocalDateTime receivedAt,
        @JsonProperty("payment_note") String paymentNote) {
}
