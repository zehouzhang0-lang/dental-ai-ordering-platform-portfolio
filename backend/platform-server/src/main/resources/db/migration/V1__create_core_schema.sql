CREATE TABLE clinic (
    clinic_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    clinic_name VARCHAR(128) NOT NULL,
    contact_name VARCHAR(64) NULL,
    contact_phone VARCHAR(32) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_clinic_name (clinic_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE customer_preference (
    preference_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    clinic_id BIGINT NOT NULL,
    preference_key VARCHAR(64) NOT NULL,
    preference_value JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_customer_preference_key (clinic_id, preference_key),
    CONSTRAINT fk_customer_preference_clinic FOREIGN KEY (clinic_id) REFERENCES clinic (clinic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE orders (
    order_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no VARCHAR(64) NOT NULL,
    clinic_id BIGINT NOT NULL,
    doctor_user_id BIGINT NULL,
    product_type VARCHAR(64) NOT NULL,
    form_data JSON NULL,
    branch_params JSON NULL,
    internal_status VARCHAR(64) NOT NULL DEFAULT 'DRAFT',
    external_status VARCHAR(64) NOT NULL DEFAULT 'DRAFT',
    production_reviewed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_orders_order_no (order_no),
    KEY idx_orders_clinic_status (clinic_id, internal_status),
    KEY idx_orders_external_status (external_status),
    CONSTRAINT fk_orders_clinic FOREIGN KEY (clinic_id) REFERENCES clinic (clinic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE order_status_history (
    history_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    from_internal_status VARCHAR(64) NULL,
    to_internal_status VARCHAR(64) NOT NULL,
    from_external_status VARCHAR(64) NULL,
    to_external_status VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    operator_user_id BIGINT NULL,
    reason VARCHAR(255) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_order_status_history_order (order_id, created_at),
    CONSTRAINT fk_order_status_history_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE order_external_projection (
    projection_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    external_status VARCHAR(64) NOT NULL,
    public_message VARCHAR(255) NULL,
    logistics_snapshot JSON NULL,
    bill_snapshot JSON NULL,
    visible_message_snapshot JSON NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_order_external_projection_order (order_id),
    KEY idx_order_external_projection_status (external_status),
    CONSTRAINT fk_order_external_projection_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE form_field_config (
    field_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    product_type VARCHAR(64) NOT NULL,
    field_key VARCHAR(64) NOT NULL,
    field_label VARCHAR(128) NOT NULL,
    field_type VARCHAR(32) NOT NULL,
    options_json JSON NULL,
    required_flag TINYINT NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_form_field_config_key (product_type, field_key),
    KEY idx_form_field_config_product (product_type, status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE workflow_chain (
    chain_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    chain_code VARCHAR(64) NOT NULL,
    chain_name VARCHAR(128) NOT NULL,
    product_type VARCHAR(64) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    intake_branch VARCHAR(32) NOT NULL DEFAULT 'BOTH',
    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_workflow_chain_code_version (chain_code, version),
    KEY idx_workflow_chain_product (product_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE workflow_node (
    node_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    chain_id BIGINT NOT NULL,
    node_code VARCHAR(96) NOT NULL,
    process_name VARCHAR(128) NOT NULL,
    stage_name VARCHAR(128) NULL,
    step_order INT NOT NULL,
    is_optional TINYINT NOT NULL DEFAULT 0,
    branch_group VARCHAR(64) NULL,
    branch_key VARCHAR(64) NULL,
    standard_duration INT NULL,
    default_role VARCHAR(64) NULL,
    node_category VARCHAR(64) NOT NULL DEFAULT 'PRODUCTION',
    need_in_check TINYINT NOT NULL DEFAULT 1,
    need_out_check TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_workflow_node_code (chain_id, node_code),
    KEY idx_workflow_node_chain_order (chain_id, step_order),
    KEY idx_workflow_node_branch (chain_id, branch_group, branch_key),
    CONSTRAINT fk_workflow_node_chain FOREIGN KEY (chain_id) REFERENCES workflow_chain (chain_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE workflow_edge (
    edge_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    chain_id BIGINT NOT NULL,
    from_node_id BIGINT NOT NULL,
    to_node_id BIGINT NOT NULL,
    edge_type VARCHAR(32) NOT NULL DEFAULT 'SEQUENCE',
    condition_key VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_workflow_edge_pair (chain_id, from_node_id, to_node_id, edge_type),
    KEY idx_workflow_edge_to (to_node_id),
    CONSTRAINT fk_workflow_edge_chain FOREIGN KEY (chain_id) REFERENCES workflow_chain (chain_id),
    CONSTRAINT fk_workflow_edge_from_node FOREIGN KEY (from_node_id) REFERENCES workflow_node (node_id),
    CONSTRAINT fk_workflow_edge_to_node FOREIGN KEY (to_node_id) REFERENCES workflow_node (node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE order_process_instance (
    instance_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    chain_id BIGINT NOT NULL,
    chain_version INT NOT NULL,
    intake_branch_used VARCHAR(32) NULL,
    branch_params JSON NULL,
    instance_status VARCHAR(64) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_order_process_instance_order (order_id),
    KEY idx_order_process_instance_chain (chain_id, chain_version),
    CONSTRAINT fk_order_process_instance_order FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT fk_order_process_instance_chain FOREIGN KEY (chain_id) REFERENCES workflow_chain (chain_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE order_process_node (
    node_instance_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    instance_id BIGINT NOT NULL,
    source_node_id BIGINT NOT NULL,
    node_code VARCHAR(96) NOT NULL,
    process_name VARCHAR(128) NOT NULL,
    stage_name VARCHAR(128) NULL,
    step_order INT NOT NULL,
    is_optional TINYINT NOT NULL DEFAULT 0,
    branch_group VARCHAR(64) NULL,
    branch_key VARCHAR(64) NULL,
    standard_duration INT NULL,
    default_role VARCHAR(64) NULL,
    node_category VARCHAR(64) NOT NULL,
    need_in_check TINYINT NOT NULL DEFAULT 1,
    need_out_check TINYINT NOT NULL DEFAULT 1,
    node_status VARCHAR(64) NOT NULL DEFAULT 'PENDING',
    assigned_user_id BIGINT NULL,
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_order_process_node_code (instance_id, node_code),
    KEY idx_order_process_node_instance_status (instance_id, node_status),
    KEY idx_order_process_node_assigned (assigned_user_id, node_status),
    CONSTRAINT fk_order_process_node_instance FOREIGN KEY (instance_id) REFERENCES order_process_instance (instance_id),
    CONSTRAINT fk_order_process_node_source FOREIGN KEY (source_node_id) REFERENCES workflow_node (node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE order_process_edge (
    edge_instance_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    instance_id BIGINT NOT NULL,
    from_node_instance_id BIGINT NOT NULL,
    to_node_instance_id BIGINT NOT NULL,
    edge_type VARCHAR(32) NOT NULL DEFAULT 'SEQUENCE',
    condition_key VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_order_process_edge_pair (instance_id, from_node_instance_id, to_node_instance_id, edge_type),
    KEY idx_order_process_edge_to (to_node_instance_id),
    CONSTRAINT fk_order_process_edge_instance FOREIGN KEY (instance_id) REFERENCES order_process_instance (instance_id),
    CONSTRAINT fk_order_process_edge_from FOREIGN KEY (from_node_instance_id) REFERENCES order_process_node (node_instance_id),
    CONSTRAINT fk_order_process_edge_to FOREIGN KEY (to_node_instance_id) REFERENCES order_process_node (node_instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE check_record (
    check_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    node_instance_id BIGINT NULL,
    check_type VARCHAR(32) NOT NULL,
    result VARCHAR(32) NOT NULL,
    checker_user_id BIGINT NULL,
    note VARCHAR(512) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_check_record_order (order_id, created_at),
    KEY idx_check_record_node (node_instance_id, check_type),
    CONSTRAINT fk_check_record_order FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT fk_check_record_node FOREIGN KEY (node_instance_id) REFERENCES order_process_node (node_instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rework_record (
    rework_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    source_check_id BIGINT NOT NULL,
    from_node_instance_id BIGINT NULL,
    target_node_instance_id BIGINT NULL,
    reason_category VARCHAR(64) NULL,
    reason_detail VARCHAR(512) NULL,
    responsibility_type VARCHAR(64) NULL,
    status VARCHAR(64) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_rework_record_order (order_id, status),
    CONSTRAINT fk_rework_record_order FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT fk_rework_record_check FOREIGN KEY (source_check_id) REFERENCES check_record (check_id),
    CONSTRAINT fk_rework_record_from_node FOREIGN KEY (from_node_instance_id) REFERENCES order_process_node (node_instance_id),
    CONSTRAINT fk_rework_record_target_node FOREIGN KEY (target_node_instance_id) REFERENCES order_process_node (node_instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE work_log (
    work_log_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    node_instance_id BIGINT NOT NULL,
    worker_user_id BIGINT NOT NULL,
    started_at DATETIME(3) NOT NULL,
    finished_at DATETIME(3) NULL,
    pause_duration_seconds INT NOT NULL DEFAULT 0,
    effective_duration_seconds INT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_work_log_worker_status (worker_user_id, status),
    KEY idx_work_log_node (node_instance_id),
    CONSTRAINT fk_work_log_order FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT fk_work_log_node FOREIGN KEY (node_instance_id) REFERENCES order_process_node (node_instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE work_log_pause_segment (
    pause_segment_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    work_log_id BIGINT NOT NULL,
    paused_at DATETIME(3) NOT NULL,
    resumed_at DATETIME(3) NULL,
    reason VARCHAR(255) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_work_log_pause_segment_log (work_log_id, paused_at),
    CONSTRAINT fk_work_log_pause_segment_log FOREIGN KEY (work_log_id) REFERENCES work_log (work_log_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE file_resource (
    file_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NULL,
    owner_user_id BIGINT NULL,
    source_type VARCHAR(64) NOT NULL,
    visibility VARCHAR(32) NOT NULL DEFAULT 'INTERNAL',
    bucket_name VARCHAR(128) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NULL,
    file_size BIGINT NULL,
    checksum VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_file_resource_object (bucket_name, object_key),
    KEY idx_file_resource_order (order_id, visibility),
    CONSTRAINT fk_file_resource_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE file_access_audit (
    audit_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_id BIGINT NOT NULL,
    order_id BIGINT NULL,
    actor_user_id BIGINT NULL,
    action VARCHAR(32) NOT NULL,
    access_result VARCHAR(32) NOT NULL,
    reason VARCHAR(255) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_file_access_audit_file (file_id, created_at),
    KEY idx_file_access_audit_order (order_id, created_at),
    CONSTRAINT fk_file_access_audit_file FOREIGN KEY (file_id) REFERENCES file_resource (file_id),
    CONSTRAINT fk_file_access_audit_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE order_message (
    message_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    sender_user_id BIGINT NULL,
    sender_role VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    visibility VARCHAR(32) NOT NULL DEFAULT 'INTERNAL',
    review_status VARCHAR(32) NOT NULL DEFAULT 'APPROVED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_order_message_order_visibility (order_id, visibility, created_at),
    CONSTRAINT fk_order_message_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE message_review_log (
    review_log_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id BIGINT NOT NULL,
    reviewer_user_id BIGINT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NOT NULL,
    reason VARCHAR(255) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_message_review_log_message (message_id, created_at),
    CONSTRAINT fk_message_review_log_message FOREIGN KEY (message_id) REFERENCES order_message (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE design_draft (
    design_draft_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    draft_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_CS_REVIEW',
    uploaded_by_user_id BIGINT NULL,
    doctor_confirmed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_design_draft_version (order_id, version_no),
    KEY idx_design_draft_status (draft_status),
    CONSTRAINT fk_design_draft_order FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT fk_design_draft_file FOREIGN KEY (file_id) REFERENCES file_resource (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE order_bill (
    bill_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    bill_no VARCHAR(64) NULL,
    amount_cent BIGINT NULL,
    currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
    bill_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    file_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_order_bill_order (order_id),
    KEY idx_order_bill_status (bill_status),
    CONSTRAINT fk_order_bill_order FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT fk_order_bill_file FOREIGN KEY (file_id) REFERENCES file_resource (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE order_logistics (
    logistics_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    carrier_name VARCHAR(64) NULL,
    tracking_no VARCHAR(128) NULL,
    logistics_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    shipped_at DATETIME(3) NULL,
    delivered_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_order_logistics_order (order_id),
    KEY idx_order_logistics_tracking (tracking_no),
    CONSTRAINT fk_order_logistics_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ai_audit_log (
    ai_audit_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NULL,
    actor_user_id BIGINT NULL,
    agent_code VARCHAR(32) NOT NULL,
    request_context_type VARCHAR(64) NOT NULL,
    prompt_hash VARCHAR(128) NULL,
    model_name VARCHAR(64) NULL,
    input_token_count INT NULL,
    output_token_count INT NULL,
    result_status VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_ai_audit_log_order (order_id, created_at),
    KEY idx_ai_audit_log_agent (agent_code, created_at),
    CONSTRAINT fk_ai_audit_log_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE notification_event (
    event_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NULL,
    event_type VARCHAR(64) NOT NULL,
    audience_role VARCHAR(32) NOT NULL,
    payload JSON NULL,
    delivery_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_notification_event_order (order_id, created_at),
    KEY idx_notification_event_status (delivery_status, created_at),
    CONSTRAINT fk_notification_event_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE user_notification (
    user_notification_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    read_at DATETIME(3) NULL,
    delivered_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_user_notification_event_user (event_id, user_id),
    KEY idx_user_notification_user_read (user_id, read_at),
    CONSTRAINT fk_user_notification_event FOREIGN KEY (event_id) REFERENCES notification_event (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
