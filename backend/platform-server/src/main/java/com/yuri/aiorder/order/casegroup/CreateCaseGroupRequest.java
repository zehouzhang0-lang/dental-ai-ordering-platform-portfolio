package com.yuri.aiorder.order.casegroup;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCaseGroupRequest(
        @JsonProperty("patient_id") @NotNull Long patientId,
        @JsonProperty("idempotency_key")
                @NotBlank
                @Size(max = 128)
                String idempotencyKey) {
}
