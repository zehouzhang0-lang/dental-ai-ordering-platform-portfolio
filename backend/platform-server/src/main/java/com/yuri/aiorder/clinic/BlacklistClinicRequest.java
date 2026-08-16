package com.yuri.aiorder.clinic;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record BlacklistClinicRequest(
        @NotBlank String reason,
        @JsonProperty("overdue_amount_cents") Long overdueAmountCents) {}
