INSERT INTO system_permission (permission_code, permission_name, module_code, status)
VALUES
    ('form:manage', '管理动态表单', 'order-form', 'ACTIVE');

INSERT INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p ON p.permission_code = 'form:manage'
LEFT JOIN system_role_permission existing
  ON existing.role_id = r.role_id
 AND existing.permission_id = p.permission_id
WHERE existing.role_id IS NULL
  AND r.role_code = 'ADMIN';

INSERT INTO system_menu
    (menu_id, parent_id, menu_code, menu_name, menu_type, route_path, component_path, permission_code, icon, sort_order, status)
VALUES
    (1810, 1800, 'form-configs', '动态表单', 'MENU', '/system/form-configs', 'FormConfigsView', 'form:manage', 'form', 81, 'ACTIVE');

INSERT INTO system_role_menu (role_id, menu_id)
SELECT DISTINCT r.role_id, m.menu_id
FROM system_role r
JOIN system_menu m ON m.menu_code = 'form-configs'
LEFT JOIN system_role_menu existing
  ON existing.role_id = r.role_id
 AND existing.menu_id = m.menu_id
WHERE existing.role_id IS NULL
  AND r.role_code = 'ADMIN';
