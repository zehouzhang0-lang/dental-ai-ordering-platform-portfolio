package com.yuri.aiorder.patient;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record UpdatePatientRequest(
        @JsonProperty("patient_name") @NotBlank @Size(max = 128) String patientName,
        @JsonProperty("patient_age") Integer patientAge,
        @JsonProperty("patient_gender") @Size(max = 32) String patientGender,
        @JsonProperty("date_of_birth") LocalDate dateOfBirth,
        @JsonProperty("phone") @Size(max = 64) String phone,
        @JsonProperty("email") @Email @Size(max = 160) String email,
        @JsonProperty("medical_notes") @Size(max = 1000) String medicalNotes,
        @JsonProperty("tags") @Size(max = 512) String tags,
        @JsonProperty("treatment_status") @Size(max = 32) String treatmentStatus,
        @JsonProperty("treatment_started_at") LocalDate treatmentStartedAt,
        @JsonProperty("treatment_ended_at") LocalDate treatmentEndedAt,
        @JsonProperty("oral_description") @Size(max = 512) String oralDescription) {
}
