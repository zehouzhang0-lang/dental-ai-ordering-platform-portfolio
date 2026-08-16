ALTER TABLE order_case_group
    ADD COLUMN submission_idempotency_key VARCHAR(128) NULL AFTER idempotency_key,
    ADD COLUMN submitted_draft_version INT NULL AFTER submission_idempotency_key,
    ADD UNIQUE KEY uk_order_case_group_submission_key
        (doctor_user_id, submission_idempotency_key);

ALTER TABLE orders
    ADD COLUMN item_client_key VARCHAR(128) NULL AFTER relationship_type,
    ADD COLUMN variant_id BIGINT NULL AFTER product_id,
    ADD UNIQUE KEY uk_orders_group_item_client_key (group_id, item_client_key),
    ADD KEY idx_orders_catalog_variant (variant_id);

ALTER TABLE orders
    ADD CONSTRAINT fk_orders_catalog_product
        FOREIGN KEY (product_id) REFERENCES catalog_product_v2 (product_id),
    ADD CONSTRAINT fk_orders_catalog_variant
        FOREIGN KEY (variant_id) REFERENCES catalog_product_variant_v2 (variant_id);

ALTER TABLE file_resource
    ADD COLUMN case_group_id BIGINT NULL AFTER order_id,
    ADD COLUMN attachment_scope VARCHAR(16) NOT NULL DEFAULT 'ORDER' AFTER case_group_id,
    ADD KEY idx_file_resource_case_group
        (case_group_id, attachment_scope, visibility, status),
    ADD CONSTRAINT fk_file_resource_case_group
        FOREIGN KEY (case_group_id) REFERENCES order_case_group (group_id),
    ADD CONSTRAINT chk_file_resource_attachment_scope
        CHECK (attachment_scope IN ('SHARED', 'ORDER'));

CREATE TABLE order_case_group_audit (
    audit_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    group_id BIGINT NOT NULL,
    order_id BIGINT NULL,
    action_type VARCHAR(64) NOT NULL,
    before_value JSON NULL,
    after_value JSON NULL,
    operator_user_id BIGINT NULL,
    idempotency_key VARCHAR(128) NULL,
    reason VARCHAR(512) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_order_case_group_audit_group (group_id, created_at),
    KEY idx_order_case_group_audit_order (order_id, created_at),
    CONSTRAINT fk_order_case_group_audit_group
        FOREIGN KEY (group_id) REFERENCES order_case_group (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
