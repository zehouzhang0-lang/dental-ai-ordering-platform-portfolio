package com.yuri.aiorder.patient;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record PatientRecordResponse(
        @JsonProperty("patient_id") long patientId,
        @JsonProperty("clinic_id") long clinicId,
        @JsonProperty("doctor_user_id") long doctorUserId,
        @JsonProperty("patient_code") String patientCode,
        @JsonProperty("patient_name") String patientName,
        @JsonProperty("patient_age") Integer patientAge,
        @JsonProperty("patient_gender") String patientGender,
        @JsonProperty("date_of_birth") LocalDate dateOfBirth,
        @JsonProperty("phone") String phone,
        @JsonProperty("email") String email,
        @JsonProperty("medical_notes") String medicalNotes,
        @JsonProperty("tags") String tags,
        @JsonProperty("treatment_status") String treatmentStatus,
        @JsonProperty("treatment_started_at") LocalDate treatmentStartedAt,
        @JsonProperty("treatment_ended_at") LocalDate treatmentEndedAt,
        @JsonProperty("oral_description") String oralDescription,
        @JsonProperty("order_count") long orderCount,
        @JsonProperty("latest_order_no") String latestOrderNo,
        @JsonProperty("latest_product_type") String latestProductType,
        @JsonProperty("latest_order_at") LocalDateTime latestOrderAt,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt) {
}
