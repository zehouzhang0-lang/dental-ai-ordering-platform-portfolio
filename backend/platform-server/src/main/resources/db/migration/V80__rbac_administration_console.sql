-- GOAL-033 / TASK-034 C 批次：管理端角色 / 权限 / 组织管理。
-- 关闭客户 CHK064 / CHK065 / CHK066 三条标红。
--
-- 客户已确认的授权边界在这里落成两个可判定的字段：
--   role_level  —— 授权等级，用来实现「经理不能分配管理员级和经理级」；
--   rbac:cross-dept —— 跨部门授权能力，用来实现「主管不能跨部门」。
-- 都是数据，不是写死的角色名判断，新增角色时只配等级与权限码即可。

ALTER TABLE system_role
    ADD COLUMN role_level INT NOT NULL DEFAULT 30 AFTER data_scope,
    ADD COLUMN remark VARCHAR(255) NULL AFTER role_level;

-- 0 = 平台管理员与入口角色（只有管理员能授予）
UPDATE system_role SET role_level = 0
WHERE role_code IN ('ADMIN', 'CS', 'WORKER', 'DOCTOR');
-- 10 = 经理级
UPDATE system_role SET role_level = 10
WHERE role_code IN ('ADMIN_MANAGER', 'CS_MANAGER', 'PROD_MANAGER');
-- 20 = 主管 / 组长 / 诊所管理员级
UPDATE system_role SET role_level = 20
WHERE role_code IN ('ADMIN_SUPERVISOR', 'PROD_SUPERVISOR', 'PROD_TEAM_LEAD', 'CLINIC_ADMIN');
-- 其余保持 30（普通岗位）

-- ---------------------------------------------------------------------------
-- 权限码：账号安全权限与业务数据权限分离（客户明确要求）
-- ---------------------------------------------------------------------------

INSERT INTO system_permission (permission_code, permission_name, module_code, status)
VALUES
    ('account:create', '创建账号', 'account-security', 'ACTIVE'),
    ('account:disable', '停用与解锁账号', 'account-security', 'ACTIVE'),
    ('account:reset-password', '重置账号密码', 'account-security', 'ACTIVE'),
    ('rbac:role:manage', '创建与维护角色', 'rbac', 'ACTIVE'),
    ('rbac:permission:assign', '分配角色的菜单、权限码与数据范围', 'rbac', 'ACTIVE'),
    ('rbac:user:assign', '给用户分配角色、部门与岗位', 'rbac', 'ACTIVE'),
    ('rbac:org:manage', '维护部门层级与岗位', 'rbac', 'ACTIVE'),
    ('rbac:matrix:read', '查看权限矩阵', 'rbac', 'ACTIVE'),
    ('rbac:cross-dept', '跨部门授权', 'rbac', 'ACTIVE')
ON DUPLICATE KEY UPDATE
    permission_name = VALUES(permission_name),
    module_code = VALUES(module_code),
    status = VALUES(status);

CREATE TEMPORARY TABLE tmp_rbac_grant (
    role_code VARCHAR(32) NOT NULL,
    permission_code VARCHAR(96) NOT NULL
) ENGINE=Memory DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO tmp_rbac_grant (role_code, permission_code) VALUES
    -- 管理员：全部
    ('ADMIN', 'account:create'), ('ADMIN', 'account:disable'), ('ADMIN', 'account:reset-password'),
    ('ADMIN', 'rbac:role:manage'), ('ADMIN', 'rbac:permission:assign'), ('ADMIN', 'rbac:user:assign'),
    ('ADMIN', 'rbac:org:manage'), ('ADMIN', 'rbac:matrix:read'), ('ADMIN', 'rbac:cross-dept'),
    -- 管理者账号（经理）：客户口径「创建/停用账号 = 管理者账号」
    ('ADMIN_MANAGER', 'account:create'), ('ADMIN_MANAGER', 'account:disable'),
    ('ADMIN_MANAGER', 'account:reset-password'),
    ('ADMIN_MANAGER', 'rbac:role:manage'), ('ADMIN_MANAGER', 'rbac:permission:assign'),
    ('ADMIN_MANAGER', 'rbac:user:assign'), ('ADMIN_MANAGER', 'rbac:org:manage'),
    ('ADMIN_MANAGER', 'rbac:matrix:read'), ('ADMIN_MANAGER', 'rbac:cross-dept'),
    -- 各部门经理：客户口径「分配角色/菜单/数据范围 = 各部门经理」，但不含账号安全权限、不跨部门
    ('CS_MANAGER', 'rbac:permission:assign'), ('CS_MANAGER', 'rbac:user:assign'),
    ('CS_MANAGER', 'rbac:matrix:read'),
    ('PROD_MANAGER', 'rbac:permission:assign'), ('PROD_MANAGER', 'rbac:user:assign'),
    ('PROD_MANAGER', 'rbac:matrix:read'),
    -- 主管：只能看矩阵，不能授权
    ('ADMIN_SUPERVISOR', 'rbac:matrix:read'),
    ('PROD_SUPERVISOR', 'rbac:matrix:read');

INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM tmp_rbac_grant g
JOIN system_role r ON r.role_code = g.role_code
JOIN system_permission p ON p.permission_code = g.permission_code;

DROP TEMPORARY TABLE tmp_rbac_grant;

-- ---------------------------------------------------------------------------
-- 高风险操作留痕
-- ---------------------------------------------------------------------------

CREATE TABLE system_rbac_audit (
    audit_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    entity_type VARCHAR(32) NOT NULL,
    entity_id BIGINT NULL,
    entity_label VARCHAR(128) NULL,
    action_type VARCHAR(48) NOT NULL,
    before_value JSON NULL,
    after_value JSON NULL,
    operator_user_id BIGINT NULL,
    operator_username VARCHAR(64) NULL,
    reason VARCHAR(512) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_system_rbac_audit_entity (entity_type, entity_id, created_at),
    KEY idx_system_rbac_audit_operator (operator_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 管理端菜单：角色权限 / 组织架构 / 权限矩阵
-- ---------------------------------------------------------------------------

-- system_menu.menu_id 不是自增列，菜单 id 一直是手工分配的，这里沿用同一约定。
INSERT INTO system_menu
    (menu_id, parent_id, menu_code, menu_name, menu_type, route_path, permission_code, icon, sort_order, status)
VALUES
    (1830, NULL, 'admin-rbac-roles', '角色权限', 'MENU', '/admin/rbac/roles', 'rbac:role:manage', 'lock', 910, 'ACTIVE'),
    (1840, NULL, 'admin-rbac-org', '组织架构', 'MENU', '/admin/rbac/org', 'rbac:org:manage', 'staff', 920, 'ACTIVE'),
    (1850, NULL, 'admin-rbac-matrix', '权限矩阵', 'MENU', '/admin/rbac/matrix', 'rbac:matrix:read', 'quality', 930, 'ACTIVE')
ON DUPLICATE KEY UPDATE
    menu_name = VALUES(menu_name),
    route_path = VALUES(route_path),
    permission_code = VALUES(permission_code),
    status = VALUES(status);

INSERT IGNORE INTO system_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM system_role r
JOIN system_menu m
WHERE r.role_code IN ('ADMIN', 'ADMIN_MANAGER')
  AND m.menu_code IN ('admin-rbac-roles', 'admin-rbac-org', 'admin-rbac-matrix');

INSERT IGNORE INTO system_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM system_role r
JOIN system_menu m
WHERE r.role_code IN ('CS_MANAGER', 'PROD_MANAGER', 'ADMIN_SUPERVISOR', 'PROD_SUPERVISOR')
  AND m.menu_code = 'admin-rbac-matrix';

-- 演示岗位：客户真实岗位清单未提供，先建可维护的基础数据。
-- system_post.post_id 同样不是自增列。
INSERT INTO system_post (post_id, post_code, post_name, sort_order, status)
VALUES
    (900, 'CS_SERVICE', '客服岗', 10, 'ACTIVE'),
    (910, 'PROD_OPERATION', '生产操作岗', 20, 'ACTIVE'),
    (920, 'PROD_QUALITY', '质检岗', 30, 'ACTIVE'),
    (930, 'LOGISTICS', '收发货岗', 40, 'ACTIVE')
ON DUPLICATE KEY UPDATE
    post_name = VALUES(post_name),
    status = VALUES(status);
