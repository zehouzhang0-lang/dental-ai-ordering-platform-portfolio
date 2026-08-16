CREATE TABLE order_payment_record (
    payment_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    amount_cents BIGINT NOT NULL,
    currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
    payment_method VARCHAR(64) NOT NULL,
    received_at DATETIME(3) NOT NULL,
    payment_note VARCHAR(255) NULL,
    created_by_user_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_order_payment_record_order (order_id, received_at),
    CONSTRAINT fk_order_payment_record_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
