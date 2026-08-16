CREATE TABLE order_case_group (
    group_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    group_no VARCHAR(80) NOT NULL,
    clinic_id BIGINT NOT NULL,
    doctor_user_id BIGINT NULL,
    patient_id BIGINT NULL,
    lifecycle_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    draft_version INT NOT NULL DEFAULT 1,
    idempotency_key VARCHAR(128) NULL,
    submitted_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_order_case_group_no (group_no),
    UNIQUE KEY uk_order_case_group_idempotency (doctor_user_id, idempotency_key),
    KEY idx_order_case_group_doctor_status (doctor_user_id, lifecycle_status, updated_at),
    KEY idx_order_case_group_clinic_status (clinic_id, lifecycle_status, updated_at),
    KEY idx_order_case_group_patient (patient_id, updated_at),
    CONSTRAINT fk_order_case_group_clinic
        FOREIGN KEY (clinic_id) REFERENCES clinic (clinic_id),
    CONSTRAINT fk_order_case_group_patient
        FOREIGN KEY (patient_id) REFERENCES patient_record (patient_id),
    CONSTRAINT chk_order_case_group_lifecycle
        CHECK (lifecycle_status IN ('DRAFT', 'SUBMITTED', 'CANCELLED')),
    CONSTRAINT chk_order_case_group_draft_version
        CHECK (draft_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO order_case_group
    (group_no, clinic_id, doctor_user_id, patient_id, lifecycle_status, submitted_at, created_at, updated_at)
SELECT
    CONCAT('CG-', orders.order_no),
    orders.clinic_id,
    orders.doctor_user_id,
    orders.patient_id,
    CASE WHEN orders.internal_status = 'DRAFT' THEN 'DRAFT' ELSE 'SUBMITTED' END,
    CASE WHEN orders.internal_status = 'DRAFT' THEN NULL ELSE orders.created_at END,
    orders.created_at,
    orders.updated_at
FROM orders
WHERE NOT EXISTS (
    SELECT 1
    FROM order_case_group existing
    WHERE existing.group_no = CONCAT('CG-', orders.order_no)
);

ALTER TABLE orders
    ADD COLUMN group_id BIGINT NULL AFTER order_id,
    ADD COLUMN line_no INT NOT NULL DEFAULT 1 AFTER group_id,
    ADD COLUMN relationship_type VARCHAR(32) NOT NULL DEFAULT 'PRIMARY' AFTER line_no,
    ADD COLUMN product_id BIGINT NULL AFTER product_type,
    ADD KEY idx_orders_group_status (group_id, internal_status, line_no),
    ADD UNIQUE KEY uk_orders_group_line (group_id, line_no),
    ADD CONSTRAINT fk_orders_case_group
        FOREIGN KEY (group_id) REFERENCES order_case_group (group_id),
    ADD CONSTRAINT chk_orders_line_no CHECK (line_no > 0),
    ADD CONSTRAINT chk_orders_relationship_type
        CHECK (relationship_type IN ('PRIMARY', 'RELATED', 'AFTER_SALES'));

UPDATE orders
JOIN order_case_group ON order_case_group.group_no = CONCAT('CG-', orders.order_no)
SET orders.group_id = order_case_group.group_id
WHERE orders.group_id IS NULL;
