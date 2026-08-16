CREATE TABLE rework_dictionary_item (
    item_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    dictionary_type VARCHAR(32) NOT NULL,
    item_code VARCHAR(64) NOT NULL,
    item_label VARCHAR(128) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_rework_dictionary_item_type_code (dictionary_type, item_code),
    KEY idx_rework_dictionary_item_type_status (dictionary_type, status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO rework_dictionary_item (dictionary_type, item_code, item_label, sort_order, status)
VALUES
    ('REASON_CATEGORY', 'FIT_ISSUE', '适配问题', 10, 'ACTIVE'),
    ('REASON_CATEGORY', 'MATERIAL_ISSUE', '材料问题', 20, 'ACTIVE'),
    ('REASON_CATEGORY', 'DESIGN_ISSUE', '设计问题', 30, 'ACTIVE'),
    ('REASON_CATEGORY', 'OTHER', '其他', 90, 'ACTIVE'),
    ('RESPONSIBILITY_TYPE', 'WORKER', '生产', 10, 'ACTIVE'),
    ('RESPONSIBILITY_TYPE', 'DOCTOR', '医生', 20, 'ACTIVE'),
    ('RESPONSIBILITY_TYPE', 'CS', '客服', 30, 'ACTIVE'),
    ('RESPONSIBILITY_TYPE', 'SYSTEM', '系统', 40, 'ACTIVE');

INSERT INTO system_permission (permission_code, permission_name, module_code, status)
VALUES ('rework:dictionary:manage', '管理返工字典', 'rework', 'ACTIVE');

INSERT INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p ON p.permission_code = 'rework:dictionary:manage'
LEFT JOIN system_role_permission existing
  ON existing.role_id = r.role_id
 AND existing.permission_id = p.permission_id
WHERE r.role_code = 'ADMIN'
  AND existing.role_id IS NULL;

INSERT INTO system_menu
    (menu_id, parent_id, menu_code, menu_name, menu_type, route_path, component_path, permission_code, icon, sort_order, status)
VALUES
    (1820, 1800, 'rework-dictionaries', '返工字典', 'MENU', '/system/rework-dictionaries', 'ReworkDictionaryManagementView', 'rework:dictionary:manage', 'dictionary', 82, 'ACTIVE');

INSERT INTO system_role_menu (role_id, menu_id)
SELECT DISTINCT r.role_id, m.menu_id
FROM system_role r
JOIN system_menu m ON m.menu_code = 'rework-dictionaries'
LEFT JOIN system_role_menu existing
  ON existing.role_id = r.role_id
 AND existing.menu_id = m.menu_id
WHERE r.role_code = 'ADMIN'
  AND existing.role_id IS NULL;
