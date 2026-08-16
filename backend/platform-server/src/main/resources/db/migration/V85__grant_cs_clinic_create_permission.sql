-- D-136 明确 CS / ADMIN 均可创建基础诊所档案。
-- V36 只把 clinic:create 授予 ADMIN，导致客服端可以打开建档表单，
-- 但正式严格权限模式下 POST /clinics 返回 403。
INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT role.role_id, permission.permission_id
FROM system_role role
JOIN system_permission permission
  ON permission.permission_code = 'clinic:create'
 AND permission.status = 'ACTIVE'
WHERE role.role_code = 'CS'
  AND role.status = 'ACTIVE';
