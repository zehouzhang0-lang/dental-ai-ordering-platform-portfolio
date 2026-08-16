package com.yuri.aiorder.collaboration;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record PaymentRecordResponse(
        @JsonProperty("payment_id") long paymentId,
        @JsonProperty("order_id") long orderId,
        @JsonProperty("amount_cents") long amountCents,
        @JsonProperty("currency") String currency,
        @JsonProperty("payment_method") String paymentMethod,
        @JsonProperty("received_at") LocalDateTime receivedAt,
        @JsonProperty("payment_note") String paymentNote,
        @JsonProperty("created_by_user_id") Long createdByUserId,
        @JsonProperty("created_at") LocalDateTime createdAt) {
}
