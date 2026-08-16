CREATE TABLE quality_record (
    quality_record_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    record_type VARCHAR(32) NOT NULL,
    source_check_id BIGINT NULL,
    rework_id BIGINT NULL,
    check_result VARCHAR(32) NULL,
    reason_category VARCHAR(64) NULL,
    reason_detail VARCHAR(512) NULL,
    responsibility_type VARCHAR(64) NULL,
    status VARCHAR(64) NOT NULL DEFAULT 'PENDING',
    status_note VARCHAR(512) NULL,
    created_by_user_id BIGINT NULL,
    status_updated_by_user_id BIGINT NULL,
    status_updated_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_quality_record_order (order_id, status, created_at),
    KEY idx_quality_record_type_status (record_type, status, created_at),
    KEY idx_quality_record_responsibility (responsibility_type, status),
    UNIQUE KEY uk_quality_record_source_check (source_check_id),
    CONSTRAINT fk_quality_record_order FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT fk_quality_record_check FOREIGN KEY (source_check_id) REFERENCES check_record (check_id),
    CONSTRAINT fk_quality_record_rework FOREIGN KEY (rework_id) REFERENCES rework_record (rework_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO quality_record
    (order_id, record_type, source_check_id, rework_id, check_result, reason_category, reason_detail,
     responsibility_type, status, created_by_user_id, status_updated_at, created_at)
SELECT
    c.order_id,
    c.check_type,
    c.check_id,
    r.rework_id,
    c.result,
    r.reason_category,
    COALESCE(r.reason_detail, c.note),
    r.responsibility_type,
    COALESCE(r.status, 'PENDING'),
    c.checker_user_id,
    r.updated_at,
    c.created_at
FROM check_record c
LEFT JOIN rework_record r ON r.source_check_id = c.check_id
WHERE c.check_type = 'EXTERNAL_RETURN'
  AND NOT EXISTS (
      SELECT 1 FROM quality_record existing WHERE existing.source_check_id = c.check_id
  );
