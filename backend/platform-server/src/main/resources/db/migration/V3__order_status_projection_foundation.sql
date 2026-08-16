ALTER TABLE orders
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER order_id,
    ADD COLUMN cs_user_id BIGINT NULL AFTER doctor_user_id,
    ADD COLUMN production_note TEXT NULL AFTER branch_params,
    ADD COLUMN reject_reason VARCHAR(512) NULL AFTER production_note;

ALTER TABLE orders
    MODIFY internal_status VARCHAR(64) NOT NULL DEFAULT 'PENDING_CS_REVIEW',
    MODIFY external_status VARCHAR(64) NOT NULL DEFAULT 'PENDING_REVIEW';

UPDATE orders
SET internal_status = 'PENDING_CS_REVIEW'
WHERE internal_status = 'DRAFT';

UPDATE orders
SET external_status = 'PENDING_REVIEW'
WHERE external_status = 'DRAFT';

CREATE INDEX idx_orders_doctor ON orders (doctor_user_id, created_at);
CREATE INDEX idx_orders_clinic_external ON orders (clinic_id, external_status, created_at);
CREATE INDEX idx_orders_internal_status ON orders (internal_status, created_at);
