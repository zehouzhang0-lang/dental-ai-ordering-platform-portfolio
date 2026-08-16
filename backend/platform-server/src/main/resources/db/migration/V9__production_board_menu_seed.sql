INSERT INTO system_menu
    (menu_id, parent_id, menu_code, menu_name, menu_type, route_path, component_path, permission_code, icon, sort_order, status)
VALUES
    (1240, 1200, 'production-board', '生产看板', 'MENU', '/production/board', 'ProductionBoardView', 'order:read-internal', 'monitor', 24, 'ACTIVE');

INSERT INTO system_role_menu (role_id, menu_id)
SELECT DISTINCT r.role_id, m.menu_id
FROM system_role r
JOIN system_menu m ON m.menu_code = 'production-board'
LEFT JOIN system_role_menu existing
  ON existing.role_id = r.role_id
 AND existing.menu_id = m.menu_id
WHERE existing.role_id IS NULL
  AND (
      r.role_code = 'ADMIN'
      OR EXISTS (
          SELECT 1
          FROM system_role_permission rp
          JOIN system_permission p ON p.permission_id = rp.permission_id
          WHERE rp.role_id = r.role_id
            AND p.permission_code = 'order:read-internal'
      )
  );
