-- Align production review and workflow execution with the confirmed PRD:
-- ADMIN keeps management/fallback capability, while only WORKER accounts with
-- a direct workflow:review-production grant receive the production review menu
-- and may execute the review.

INSERT INTO system_permission (permission_code, permission_name, module_code, status)
VALUES
    ('workflow:review-production', '生产审核', 'workflow', 'ACTIVE'),
    ('workflow:skip-optional', '跳过本人可选工序', 'workflow', 'ACTIVE')
ON DUPLICATE KEY UPDATE
    permission_name = VALUES(permission_name),
    module_code = VALUES(module_code),
    status = VALUES(status);

DELETE role_permission
FROM system_role_permission role_permission
JOIN system_role role ON role.role_id = role_permission.role_id
JOIN system_permission permission ON permission.permission_id = role_permission.permission_id
WHERE role.role_code = 'WORKER'
  AND permission.permission_code IN (
      'workflow:review-production',
      'final-inspection:manage'
  );

INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT role.role_id, permission.permission_id
FROM system_role role
JOIN system_permission permission
  ON permission.permission_code IN (
      'workflow:review-production',
      'workflow:skip-optional'
  )
WHERE role.role_code = 'ADMIN';

INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT role.role_id, permission.permission_id
FROM system_role role
JOIN system_permission permission
  ON permission.permission_code = 'workflow:skip-optional'
WHERE role.role_code = 'WORKER';

-- The WORKER role is allowed to own the menu, but the login menu loader also
-- checks effective permissions, so ordinary workers do not receive this item.
INSERT IGNORE INTO system_role_menu (role_id, menu_id)
SELECT role.role_id, menu.menu_id
FROM system_role role
JOIN system_menu menu ON menu.menu_code = 'production-review'
WHERE role.role_code = 'WORKER';

-- A dedicated snapshot node represents the phase-2 design-confirmation gate.
-- It is connected to the selected route roots at instance creation and is
-- completed only after the doctor confirms the internally approved design.
INSERT INTO workflow_node
    (chain_id, node_code, process_name, stage_name, step_order, is_optional,
     branch_group, branch_key, standard_duration, default_role, node_category,
     need_in_check, need_out_check)
SELECT
    chain.chain_id,
    CONCAT(chain.chain_code, '_DESIGN_CONFIRMATION_GATE'),
    '设计稿确认',
    '设计审核',
    -10,
    0,
    NULL,
    NULL,
    NULL,
    'WORKER',
    'DESIGN_GATE',
    0,
    0
FROM workflow_chain chain
WHERE chain.status = 1
  AND NOT EXISTS (
      SELECT 1
      FROM workflow_node existing
      WHERE existing.chain_id = chain.chain_id
        AND existing.node_category = 'DESIGN_GATE'
  );

ALTER TABLE design_task
    ADD COLUMN node_instance_id BIGINT NULL AFTER order_id,
    ADD UNIQUE KEY uk_design_task_node_instance (node_instance_id),
    ADD CONSTRAINT fk_design_task_node_instance
        FOREIGN KEY (node_instance_id) REFERENCES order_process_node (node_instance_id);

CREATE TABLE workflow_assignment_event (
    assignment_event_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    node_instance_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    from_user_id BIGINT NULL,
    to_user_id BIGINT NOT NULL,
    reason VARCHAR(500) NULL,
    actor_user_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_workflow_assignment_order (order_id, created_at),
    KEY idx_workflow_assignment_node (node_instance_id, created_at),
    KEY idx_workflow_assignment_target (to_user_id, created_at),
    CONSTRAINT fk_workflow_assignment_order
        FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT fk_workflow_assignment_node
        FOREIGN KEY (node_instance_id) REFERENCES order_process_node (node_instance_id),
    CONSTRAINT fk_workflow_assignment_from_user
        FOREIGN KEY (from_user_id) REFERENCES system_user (user_id),
    CONSTRAINT fk_workflow_assignment_to_user
        FOREIGN KEY (to_user_id) REFERENCES system_user (user_id),
    CONSTRAINT fk_workflow_assignment_actor
        FOREIGN KEY (actor_user_id) REFERENCES system_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
