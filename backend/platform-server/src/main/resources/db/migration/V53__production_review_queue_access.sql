-- 生产审核是生产端的前置职责：审核人可见待审核订单，但普通未派工订单仍保持 SELF 数据范围。
INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p ON p.permission_code = 'workflow:review-production'
WHERE r.role_code = 'WORKER';

INSERT IGNORE INTO system_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM system_role r
JOIN system_menu m ON m.menu_code = 'production-review'
WHERE r.role_code = 'WORKER';
