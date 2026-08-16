-- 已确认口径：技术设计内审由生产侧获授权组长执行；客服只查看医生可见版本与进度。
DELETE role_permission
FROM system_role_permission role_permission
JOIN system_role role ON role.role_id = role_permission.role_id
JOIN system_permission permission ON permission.permission_id = role_permission.permission_id
WHERE role.role_code = 'CS_MANAGER'
  AND permission.permission_code = 'design-draft:internal-review';

INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT role.role_id, permission.permission_id
FROM system_role role
JOIN system_permission permission
  ON permission.permission_code = 'design-draft:internal-review'
WHERE role.role_code = 'PROD_TEAM_LEAD';
