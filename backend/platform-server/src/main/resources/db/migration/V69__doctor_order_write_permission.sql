INSERT INTO system_permission (
    permission_code,
    permission_name,
    module_code,
    status
)
VALUES (
    'order:write-doctor',
    '医生维护本人订单',
    'order',
    'ACTIVE'
)
ON DUPLICATE KEY UPDATE
    permission_name = VALUES(permission_name),
    module_code = VALUES(module_code),
    status = VALUES(status);

INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT role.role_id, permission.permission_id
FROM system_role role
JOIN system_permission permission
  ON permission.permission_code = 'order:write-doctor'
WHERE role.role_code = 'DOCTOR'
  AND role.status = 'ACTIVE';
