package com.yuri.aiorder.collaboration;

import com.fasterxml.jackson.annotation.JsonProperty;

public record BillResponse(
        @JsonProperty("bill_id") Long billId,
        @JsonProperty("order_id") long orderId,
        @JsonProperty("bill_status") String billStatus,
        @JsonProperty("payment_status") String paymentStatus,
        @JsonProperty("amount_cents") Long amountCents,
        @JsonProperty("currency") String currency,
        @JsonProperty("file_id") Long fileId) {
}
