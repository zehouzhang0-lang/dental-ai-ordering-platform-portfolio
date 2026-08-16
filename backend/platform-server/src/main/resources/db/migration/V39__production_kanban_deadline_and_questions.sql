ALTER TABLE order_process_node
    ADD COLUMN deadline_at DATETIME(3) NULL AFTER started_at,
    ADD KEY idx_order_process_node_deadline (node_status, deadline_at);

CREATE TABLE production_question (
    question_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    node_instance_id BIGINT NOT NULL,
    content VARCHAR(1000) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    asked_by_user_id BIGINT NULL,
    asked_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    resolved_by_user_id BIGINT NULL,
    resolved_at DATETIME(3) NULL,
    resolution_note VARCHAR(1000) NULL,
    KEY idx_production_question_node_status (node_instance_id, status, asked_at),
    KEY idx_production_question_order_status (order_id, status, asked_at),
    CONSTRAINT fk_production_question_order FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT fk_production_question_node FOREIGN KEY (node_instance_id) REFERENCES order_process_node (node_instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
