ALTER TABLE orders
    ADD COLUMN draft_deleted_at DATETIME(3) NULL AFTER external_status,
    ADD COLUMN draft_deleted_by BIGINT NULL AFTER draft_deleted_at,
    ADD KEY idx_orders_doctor_draft_visible
        (doctor_user_id, draft_deleted_at, external_status, created_at);

CREATE TABLE order_cancellation_request (
    request_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    requester_user_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    request_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    resolved_by_user_id BIGINT NULL,
    resolution_note VARCHAR(500) NULL,
    resolved_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_order_cancellation_request_order (order_id, request_status, created_at),
    KEY idx_order_cancellation_request_status (request_status, created_at),
    CONSTRAINT fk_order_cancellation_request_order
        FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT chk_order_cancellation_request_status
        CHECK (request_status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
