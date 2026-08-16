-- GOAL-033 / TASK-034 D 批次：账号交接与人员转移。
--
-- 客户原话：「有分配功能，把他账号分配给新同事，并保留之前得服务记录」。
-- 后半句是这一批真正的难点：**只转移「当前负责关系」，不改「历史事实」**。
-- 已完成的工序、工时、质检、返工、审核记录必须保留原操作人，否则绩效归属与
-- 责任追溯会被一次交接静默改写——而且改完不报错，等到查绩效才发现对不上。
--
-- 全库共 78 个带用户 ID 的列，逐个判定后只有 9 个属于「当前负责关系」，
-- 其余全部是历史事实。分类结果写在 AccountHandoverPlan 里，并由
-- AccountHandoverClassificationTests 扫 information_schema 强制**每一列都被显式分类**：
-- 以后任何人新增一个用户 ID 列，不分类就过不了测试，而不是默默漏掉。
--
-- 两张表：
--   account_handover      —— 一次交接：原责任人、承接人、操作人、时间、原因；
--   account_handover_item —— 转移对象清单：每类对象转了哪些、多少条。
-- 两者合起来就是客户要求的留痕六项。

CREATE TABLE account_handover (
    handover_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    handover_no VARCHAR(64) NOT NULL,
    from_user_id BIGINT NOT NULL,
    to_user_id BIGINT NOT NULL,
    operator_user_id BIGINT NOT NULL,
    reason VARCHAR(512) NULL,
    source_disabled TINYINT(1) NOT NULL DEFAULT 0,
    transferred_object_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_account_handover_no (handover_no),
    KEY idx_account_handover_from (from_user_id, created_at),
    KEY idx_account_handover_to (to_user_id, created_at),
    KEY idx_account_handover_operator (operator_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- object_ids 落具体主键清单，客户要的「转移对象清单」指的就是这个：
-- 只记「转移了 12 条订单」在事后追溯时没有用，得知道是哪 12 条。
CREATE TABLE account_handover_item (
    handover_item_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    handover_id BIGINT NOT NULL,
    object_type VARCHAR(64) NOT NULL,
    object_label VARCHAR(128) NOT NULL,
    target_table VARCHAR(64) NOT NULL,
    target_column VARCHAR(64) NOT NULL,
    affected_count INT NOT NULL,
    object_ids JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_account_handover_item (handover_id, object_type),
    CONSTRAINT fk_account_handover_item FOREIGN KEY (handover_id)
        REFERENCES account_handover (handover_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 权限码
-- ---------------------------------------------------------------------------
-- 归入 C 批次已经划好的「账号安全权限」一组（account:create / account:disable /
-- account:reset-password），与业务数据权限分开，只给管理者账号。

INSERT INTO system_permission (permission_code, permission_name, module_code, status)
VALUES
    ('account:handover', '账号交接与人员转移', 'account-security', 'ACTIVE'),
    ('account:handover:read', '查看账号交接记录', 'account-security', 'ACTIVE')
ON DUPLICATE KEY UPDATE
    permission_name = VALUES(permission_name),
    module_code = VALUES(module_code),
    status = VALUES(status);

INSERT INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p ON p.permission_code IN ('account:handover', 'account:handover:read')
WHERE r.role_code IN ('ADMIN', 'ADMIN_MANAGER')
  AND NOT EXISTS (
      SELECT 1 FROM system_role_permission existing
      WHERE existing.role_id = r.role_id
        AND existing.permission_id = p.permission_id
  );

-- ---------------------------------------------------------------------------
-- 管理端菜单
-- ---------------------------------------------------------------------------
-- system_menu.menu_id 不是自增列，沿用手工分配的既有约定（见 V80 的说明）。

INSERT INTO system_menu
    (menu_id, parent_id, menu_code, menu_name, menu_type, route_path, permission_code, icon, sort_order, status)
VALUES
    (1880, NULL, 'admin-account-handover', '账号交接', 'MENU', '/admin/account/handover', 'account:handover', 'staff', 960, 'ACTIVE')
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
  AND m.menu_code = 'admin-account-handover';
