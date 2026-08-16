-- The confirmed phase-2 baseline keeps assignment, reassignment and optional
-- production-node skipping under ADMIN responsibility.

UPDATE system_permission
SET permission_name = '管理员跳过可选工序',
    module_code = 'workflow',
    status = 'ACTIVE'
WHERE permission_code = 'workflow:skip-optional';

DELETE role_permission
FROM system_role_permission role_permission
JOIN system_role role ON role.role_id = role_permission.role_id
JOIN system_permission permission ON permission.permission_id = role_permission.permission_id
WHERE role.role_code = 'WORKER'
  AND permission.permission_code = 'workflow:skip-optional';

DELETE user_permission
FROM system_user_permission user_permission
JOIN system_user user_account ON user_account.user_id = user_permission.user_id
JOIN system_permission permission ON permission.permission_id = user_permission.permission_id
WHERE user_account.user_type = 'WORKER'
  AND permission.permission_code = 'workflow:skip-optional';
