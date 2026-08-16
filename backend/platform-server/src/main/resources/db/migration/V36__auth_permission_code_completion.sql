INSERT INTO system_permission (permission_code, permission_name, module_code, status)
VALUES
    ('clinic:read-internal', '读取内部客户诊所', 'clinic', 'ACTIVE'),
    ('clinic:read-self', '读取本人诊所', 'clinic', 'ACTIVE'),
    ('clinic:create', '创建诊所', 'clinic', 'ACTIVE'),
    ('clinic:preference:write', '维护诊所偏好', 'clinic', 'ACTIVE'),
    ('account:doctor', '维护医生本人账户', 'account', 'ACTIVE'),
    ('notification:read-self', '读取本人通知', 'notification', 'ACTIVE'),
    ('notification:write-self', '更新本人通知', 'notification', 'ACTIVE')
ON DUPLICATE KEY UPDATE
    permission_name = VALUES(permission_name),
    module_code = VALUES(module_code),
    status = VALUES(status);

INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p
WHERE r.role_code = 'ADMIN'
  AND p.permission_code IN (
      'clinic:read-internal',
      'clinic:read-self',
      'clinic:create',
      'clinic:preference:write',
      'account:doctor',
      'notification:read-self',
      'notification:write-self'
  );

INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p ON p.permission_code IN (
    'clinic:read-internal',
    'clinic:preference:write',
    'notification:read-self',
    'notification:write-self'
)
WHERE r.role_code = 'CS';

INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p ON p.permission_code IN (
    'notification:read-self',
    'notification:write-self'
)
WHERE r.role_code = 'WORKER';

INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p ON p.permission_code IN (
    'clinic:read-self',
    'account:doctor',
    'notification:read-self',
    'notification:write-self'
)
WHERE r.role_code = 'DOCTOR';
