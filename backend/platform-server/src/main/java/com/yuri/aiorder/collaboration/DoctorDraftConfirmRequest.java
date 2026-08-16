package com.yuri.aiorder.collaboration;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DoctorDraftConfirmRequest(
        String action,
        @JsonProperty("doctor_reject_reason") String doctorRejectReason) {
}
