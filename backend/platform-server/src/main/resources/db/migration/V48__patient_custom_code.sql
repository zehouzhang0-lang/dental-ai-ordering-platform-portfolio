ALTER TABLE patient_record
    ADD COLUMN patient_code VARCHAR(32) NULL AFTER doctor_user_id,
    ADD UNIQUE KEY uk_patient_record_clinic_code (clinic_id, patient_code);
