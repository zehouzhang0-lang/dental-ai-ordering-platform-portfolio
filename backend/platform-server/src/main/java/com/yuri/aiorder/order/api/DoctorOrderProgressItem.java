package com.yuri.aiorder.order.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record DoctorOrderProgressItem(
        String key,
        String label,
        String status,
        @JsonProperty("occurred_at") LocalDateTime occurredAt,
        String note) {
}
