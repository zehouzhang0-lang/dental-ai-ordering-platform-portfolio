CREATE TABLE final_inspection_report (
    report_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    report_no VARCHAR(64) NOT NULL,
    final_node_instance_id BIGINT NOT NULL,
    final_check_id BIGINT NOT NULL,
    conclusion VARCHAR(32) NOT NULL DEFAULT 'PASS',
    summary VARCHAR(512) NULL,
    inspector_user_id BIGINT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ISSUED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_final_inspection_report_order (order_id),
    UNIQUE KEY uk_final_inspection_report_no (report_no),
    KEY idx_final_inspection_report_node (final_node_instance_id),
    CONSTRAINT fk_final_inspection_report_order FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT fk_final_inspection_report_node FOREIGN KEY (final_node_instance_id) REFERENCES order_process_node (node_instance_id),
    CONSTRAINT fk_final_inspection_report_check FOREIGN KEY (final_check_id) REFERENCES check_record (check_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
