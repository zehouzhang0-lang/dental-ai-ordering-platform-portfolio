ALTER TABLE order_bill
    ADD COLUMN payment_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_PAYMENT' AFTER bill_status,
    ADD KEY idx_order_bill_payment_status (payment_status);
