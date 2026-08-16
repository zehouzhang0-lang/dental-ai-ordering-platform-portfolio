ALTER TABLE production_equipment_event
    ADD COLUMN requested_by_user_id BIGINT NULL AFTER description,
    ADD COLUMN approved_by_user_id BIGINT NULL AFTER requested_by_user_id,
    ADD COLUMN decision_note VARCHAR(512) NULL AFTER approved_by_user_id,
    ADD COLUMN decided_at DATETIME(3) NULL AFTER decision_note,
    ADD KEY idx_equipment_event_approval (event_type, status, created_at);

CREATE TABLE production_safety_rule (
    rule_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_code VARCHAR(64) NOT NULL,
    rule_name VARCHAR(128) NOT NULL,
    check_type VARCHAR(32) NOT NULL,
    department_name VARCHAR(128) NOT NULL,
    cycle_type VARCHAR(32) NOT NULL,
    cycle_interval INT NOT NULL DEFAULT 1,
    responsible_owner VARCHAR(128) NULL,
    next_due_at DATETIME(3) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_safety_rule_code (rule_code),
    KEY idx_safety_rule_department_status (department_name, status),
    KEY idx_safety_rule_next_due (next_due_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE production_outsourcing_batch (
    outsourcing_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_no VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    item_name VARCHAR(128) NOT NULL,
    supplier_name VARCHAR(128) NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL DEFAULT 'SENT',
    sent_at DATETIME(3) NOT NULL,
    expected_return_at DATETIME(3) NULL,
    actual_return_at DATETIME(3) NULL,
    abnormal_note VARCHAR(512) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_outsourcing_batch_no (batch_no),
    KEY idx_outsourcing_order (order_id, status),
    KEY idx_outsourcing_expected_return (expected_return_at, status),
    CONSTRAINT fk_outsourcing_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
