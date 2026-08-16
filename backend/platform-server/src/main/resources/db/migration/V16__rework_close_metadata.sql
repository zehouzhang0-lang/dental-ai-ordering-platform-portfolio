ALTER TABLE rework_record
    ADD COLUMN close_note VARCHAR(512) NULL AFTER responsibility_type,
    ADD COLUMN closed_by_user_id BIGINT NULL AFTER close_note,
    ADD COLUMN closed_at DATETIME(3) NULL AFTER closed_by_user_id,
    ADD KEY idx_rework_record_closed (status, closed_at);
