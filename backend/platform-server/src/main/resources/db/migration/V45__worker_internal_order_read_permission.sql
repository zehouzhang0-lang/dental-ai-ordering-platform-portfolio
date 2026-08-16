-- 生产员工需要读取与本人工序分配相关的内部订单。
-- 订单查询仍由 WORKER 的 SELF data scope 限制，不会扩大为全部订单。
INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p ON p.permission_code = 'order:read-internal'
WHERE r.role_code = 'WORKER';
