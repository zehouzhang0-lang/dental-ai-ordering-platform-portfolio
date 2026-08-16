ALTER TABLE order_process_node
    ADD COLUMN skipped_at DATETIME(3) NULL AFTER completed_at,
    ADD COLUMN skip_reason VARCHAR(255) NULL AFTER skipped_at;
