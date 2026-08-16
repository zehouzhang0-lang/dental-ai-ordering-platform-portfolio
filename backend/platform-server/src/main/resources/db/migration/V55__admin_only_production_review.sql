-- Production review is a management responsibility. Production workers only
-- enter the unassigned production pool after an ADMIN has created the process.
DELETE role_permission
FROM system_role_permission role_permission
JOIN system_role role ON role.role_id = role_permission.role_id
JOIN system_permission permission ON permission.permission_id = role_permission.permission_id
WHERE role.role_code IN ('CS', 'WORKER')
  AND permission.permission_code = 'workflow:review-production';

DELETE role_menu
FROM system_role_menu role_menu
JOIN system_role role ON role.role_id = role_menu.role_id
JOIN system_menu menu ON menu.menu_id = role_menu.menu_id
WHERE role.role_code IN ('CS', 'WORKER')
  AND menu.menu_code = 'production-review';

DELETE user_permission
FROM system_user_permission user_permission
JOIN system_user user_account ON user_account.user_id = user_permission.user_id
JOIN system_permission permission ON permission.permission_id = user_permission.permission_id
WHERE user_account.user_type <> 'ADMIN'
  AND permission.permission_code = 'workflow:review-production';
