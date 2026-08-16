CREATE TABLE patient_record (
    patient_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    clinic_id BIGINT NOT NULL,
    doctor_user_id BIGINT NOT NULL,
    patient_name VARCHAR(128) NOT NULL,
    patient_age INT NULL,
    patient_gender VARCHAR(32) NULL,
    oral_description VARCHAR(512) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_patient_record_doctor_name (doctor_user_id, patient_name),
    KEY idx_patient_record_clinic_status (clinic_id, status, updated_at),
    CONSTRAINT fk_patient_record_clinic FOREIGN KEY (clinic_id) REFERENCES clinic (clinic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE orders
    ADD COLUMN patient_id BIGINT NULL AFTER doctor_user_id,
    ADD KEY idx_orders_patient (patient_id, created_at),
    ADD CONSTRAINT fk_orders_patient FOREIGN KEY (patient_id) REFERENCES patient_record (patient_id);

INSERT INTO system_permission (permission_code, permission_name, module_code, status)
SELECT 'patient:manage-doctor', '医生患者档案管理', 'patient', 'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM system_permission WHERE permission_code = 'patient:manage-doctor'
);

INSERT INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p ON p.permission_code = 'patient:manage-doctor'
LEFT JOIN system_role_permission existing
  ON existing.role_id = r.role_id
 AND existing.permission_id = p.permission_id
WHERE r.role_code IN ('ADMIN', 'DOCTOR')
  AND existing.role_id IS NULL;

INSERT INTO system_menu
    (menu_id, parent_id, menu_code, menu_name, menu_type, route_path, component_path, permission_code, icon, sort_order, status)
SELECT 1110, NULL, 'doctor-patients', '患者管理', 'MENU', '/doctor/patients', 'DoctorPatientsView',
       'patient:manage-doctor', 'customer', 11, 'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM system_menu WHERE menu_code = 'doctor-patients'
);

INSERT INTO system_role_menu (role_id, menu_id)
SELECT DISTINCT r.role_id, m.menu_id
FROM system_role r
JOIN system_menu m ON m.menu_code = 'doctor-patients'
LEFT JOIN system_role_menu existing
  ON existing.role_id = r.role_id
 AND existing.menu_id = m.menu_id
WHERE r.role_code IN ('ADMIN', 'DOCTOR')
  AND existing.role_id IS NULL;
