package com.yuri.aiorder.collaboration;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PaymentStatusRequest(@JsonProperty("payment_status") String paymentStatus) {
}
