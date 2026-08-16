package com.yuri.aiorder.account;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DoctorPasswordUpdateRequest(
        @JsonProperty("current_password") String currentPassword,
        @JsonProperty("new_password") String newPassword) {
}
