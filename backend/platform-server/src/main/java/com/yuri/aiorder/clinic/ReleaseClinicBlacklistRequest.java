package com.yuri.aiorder.clinic;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record ReleaseClinicBlacklistRequest(@JsonProperty("release_reason") @NotBlank String releaseReason) {}
