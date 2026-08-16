INSERT INTO system_permission (permission_code, permission_name, module_code, status)
SELECT 'staff:manage', '人员账号管理', 'staff', 'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM system_permission WHERE permission_code = 'staff:manage'
);

INSERT INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p ON p.permission_code = 'staff:manage'
WHERE r.role_code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1
      FROM system_role_permission rp
      WHERE rp.role_id = r.role_id
        AND rp.permission_id = p.permission_id
  );
