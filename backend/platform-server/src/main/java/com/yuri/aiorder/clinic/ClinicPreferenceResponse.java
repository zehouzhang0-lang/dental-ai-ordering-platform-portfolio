package com.yuri.aiorder.clinic;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.Map;

public record ClinicPreferenceResponse(
        @JsonProperty("clinic_id") long clinicId,
        @JsonProperty("clinic_name") String clinicName,
        Map<String, Object> preferences,
        @JsonProperty("updated_at") LocalDateTime updatedAt) {}
