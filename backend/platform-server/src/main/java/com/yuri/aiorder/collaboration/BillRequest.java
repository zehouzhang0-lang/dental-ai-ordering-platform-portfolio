package com.yuri.aiorder.collaboration;

import com.fasterxml.jackson.annotation.JsonProperty;

public record BillRequest(
        @JsonProperty("file_id") Long fileId,
        @JsonProperty("amount_cents") Long amountCents,
        @JsonProperty("currency") String currency) {

    public BillRequest(Long fileId) {
        this(fileId, null, null);
    }
}
