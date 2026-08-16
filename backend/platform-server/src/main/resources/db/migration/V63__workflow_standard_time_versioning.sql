CREATE TABLE workflow_standard_time_version (
    standard_time_version_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    version_no INT NOT NULL,
    version_name VARCHAR(128) NOT NULL,
    publication_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    based_on_version_id BIGINT NULL,
    effective_at DATETIME(3) NULL,
    published_at DATETIME(3) NULL,
    published_by_user_id BIGINT NULL,
    lock_version INT NOT NULL DEFAULT 0,
    created_by_user_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_workflow_standard_time_version_no (version_no),
    KEY idx_workflow_standard_time_status (publication_status, effective_at),
    CONSTRAINT fk_workflow_standard_time_based_on
        FOREIGN KEY (based_on_version_id)
        REFERENCES workflow_standard_time_version (standard_time_version_id),
    CONSTRAINT chk_workflow_standard_time_publication
        CHECK (publication_status IN ('DRAFT', 'ACTIVE', 'INACTIVE')),
    CONSTRAINT chk_workflow_standard_time_lock CHECK (lock_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE workflow_standard_time_item (
    standard_time_item_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    standard_time_version_id BIGINT NOT NULL,
    chain_id BIGINT NOT NULL,
    node_id BIGINT NOT NULL,
    standard_duration_minutes INT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    lock_version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_workflow_standard_time_version_node (standard_time_version_id, node_id),
    KEY idx_workflow_standard_time_chain (standard_time_version_id, chain_id, status),
    CONSTRAINT fk_workflow_standard_time_item_version
        FOREIGN KEY (standard_time_version_id)
        REFERENCES workflow_standard_time_version (standard_time_version_id),
    CONSTRAINT fk_workflow_standard_time_item_chain
        FOREIGN KEY (chain_id) REFERENCES workflow_chain (chain_id),
    CONSTRAINT fk_workflow_standard_time_item_node
        FOREIGN KEY (node_id) REFERENCES workflow_node (node_id),
    CONSTRAINT chk_workflow_standard_time_minutes
        CHECK (standard_duration_minutes IS NULL
            OR (standard_duration_minutes >= 0 AND standard_duration_minutes <= 525600)),
    CONSTRAINT chk_workflow_standard_time_item_status
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT chk_workflow_standard_time_item_lock CHECK (lock_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE workflow_standard_time_audit (
    audit_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    standard_time_version_id BIGINT NULL,
    standard_time_item_id BIGINT NULL,
    action_type VARCHAR(32) NOT NULL,
    before_value JSON NULL,
    after_value JSON NULL,
    operator_user_id BIGINT NULL,
    reason VARCHAR(512) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_workflow_standard_time_audit_version (standard_time_version_id, created_at),
    CONSTRAINT fk_workflow_standard_time_audit_version
        FOREIGN KEY (standard_time_version_id)
        REFERENCES workflow_standard_time_version (standard_time_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO workflow_standard_time_version
    (version_no, version_name, publication_status)
SELECT 1, '标准工时空值草稿 2026-07-31', 'DRAFT'
WHERE NOT EXISTS (
    SELECT 1 FROM workflow_standard_time_version WHERE version_no = 1
);

INSERT INTO workflow_standard_time_item
    (standard_time_version_id, chain_id, node_id, standard_duration_minutes)
SELECT
    version.standard_time_version_id,
    node.chain_id,
    node.node_id,
    NULL
FROM workflow_standard_time_version version
JOIN workflow_node node
WHERE version.version_no = 1
  AND NOT EXISTS (
      SELECT 1
      FROM workflow_standard_time_item existing
      WHERE existing.standard_time_version_id = version.standard_time_version_id
        AND existing.node_id = node.node_id
  );

INSERT INTO system_permission (permission_code, permission_name, module_code, status)
SELECT 'workflow:standard-time:manage', '工序标准工时维护', 'workflow', 'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1
    FROM system_permission
    WHERE permission_code = 'workflow:standard-time:manage'
);

INSERT INTO system_role_permission (role_id, permission_id)
SELECT role.role_id, permission.permission_id
FROM system_role role
JOIN system_permission permission
  ON permission.permission_code = 'workflow:standard-time:manage'
LEFT JOIN system_role_permission existing
  ON existing.role_id = role.role_id
 AND existing.permission_id = permission.permission_id
WHERE role.role_code = 'ADMIN'
  AND existing.role_id IS NULL;

INSERT INTO system_menu
    (menu_id, parent_id, menu_code, menu_name, menu_type, route_path,
     component_path, permission_code, icon, sort_order, status)
SELECT
    1140, NULL, 'workflow-standard-time', '标准工时', 'MENU',
    '/admin/workflow/standard-time', 'WorkflowStandardTimeView',
    'workflow:standard-time:manage', 'timer', 14, 'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM system_menu WHERE menu_code = 'workflow-standard-time'
);

INSERT INTO system_role_menu (role_id, menu_id)
SELECT role.role_id, menu.menu_id
FROM system_role role
JOIN system_menu menu ON menu.menu_code = 'workflow-standard-time'
LEFT JOIN system_role_menu existing
  ON existing.role_id = role.role_id
 AND existing.menu_id = menu.menu_id
WHERE role.role_code = 'ADMIN'
  AND existing.role_id IS NULL;
