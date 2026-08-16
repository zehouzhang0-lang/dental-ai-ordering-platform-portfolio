-- 生产端只能操作订单沟通消息；不得因此获得账单、设计稿审核或其他客服协同权限。
INSERT INTO system_permission (permission_code, permission_name, module_code, status)
VALUES ('message:operate-production', '生产订单沟通', 'collaboration', 'ACTIVE')
ON DUPLICATE KEY UPDATE
    permission_name = VALUES(permission_name),
    module_code = VALUES(module_code),
    status = VALUES(status);

INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT role.role_id, permission.permission_id
FROM system_role role
JOIN system_permission permission ON permission.permission_code = 'message:operate-production'
WHERE role.role_code = 'WORKER';

INSERT IGNORE INTO system_role_menu (role_id, menu_id)
SELECT role.role_id, menu.menu_id
FROM system_role role
JOIN system_menu menu ON menu.menu_code = 'collaboration'
WHERE role.role_code = 'WORKER';
