INSERT INTO system_permission (permission_code, permission_name, module_code, status)
VALUES ('workflow:operate-business-gate', '处理客服业务门禁', 'workflow', 'ACTIVE')
ON DUPLICATE KEY UPDATE
    permission_name = VALUES(permission_name),
    module_code = VALUES(module_code),
    status = VALUES(status);

INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT role.role_id, permission.permission_id
FROM system_role role
JOIN system_permission permission
  ON permission.permission_code = 'workflow:operate-business-gate'
WHERE role.role_code IN ('ADMIN', 'CS');

CREATE TABLE workflow_business_gate_audit (
    business_gate_audit_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    node_instance_id BIGINT NOT NULL,
    process_name VARCHAR(128) NOT NULL,
    node_category VARCHAR(64) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    action_note VARCHAR(500) NOT NULL,
    actor_user_id BIGINT NULL,
    actor_role VARCHAR(32) NOT NULL,
    before_status VARCHAR(64) NOT NULL,
    after_status VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_workflow_business_gate_order (order_id, created_at),
    KEY idx_workflow_business_gate_node (node_instance_id, created_at),
    KEY idx_workflow_business_gate_actor (actor_user_id, created_at),
    CONSTRAINT fk_workflow_business_gate_order
        FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT fk_workflow_business_gate_node
        FOREIGN KEY (node_instance_id) REFERENCES order_process_node (node_instance_id),
    CONSTRAINT fk_workflow_business_gate_actor
        FOREIGN KEY (actor_user_id) REFERENCES system_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
