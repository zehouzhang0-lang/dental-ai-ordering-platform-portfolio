ALTER TABLE patient_record
    ADD COLUMN date_of_birth DATE NULL AFTER patient_gender,
    ADD COLUMN phone VARCHAR(64) NULL AFTER date_of_birth,
    ADD COLUMN email VARCHAR(160) NULL AFTER phone,
    ADD COLUMN medical_notes VARCHAR(1000) NULL AFTER email,
    ADD COLUMN patient_tags VARCHAR(512) NULL AFTER medical_notes,
    ADD COLUMN treatment_status VARCHAR(32) NOT NULL DEFAULT 'IN_TREATMENT' AFTER patient_tags,
    ADD COLUMN treatment_started_at DATE NULL AFTER treatment_status,
    ADD COLUMN treatment_ended_at DATE NULL AFTER treatment_started_at,
    ADD KEY idx_patient_record_treatment (doctor_user_id, treatment_status, updated_at),
    ADD KEY idx_patient_record_contact (clinic_id, phone, email);

UPDATE patient_record
SET treatment_started_at = DATE(created_at)
WHERE treatment_started_at IS NULL;
