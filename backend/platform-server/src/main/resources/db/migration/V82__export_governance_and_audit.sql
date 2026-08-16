-- GOAL-033 / TASK-034 E 批次：导出管控与留痕。
--
-- 客户原话三条：
--   「不允许医生直接导出，只能由管理者账号导出，并且导出需要反复确认」
--   「客户信息、地址、账单的导出是需要批准的」
--   「各个管理端都需要数据导出，除了客户信息、价格等，别的数据需要导出留痕」
--
-- 执行前的现状核实：后端**一个导出接口都没有**（Controller 里 export/csv/excel 零命中），
-- 而医生端有两个纯前端拼 CSV 的按钮，点一下就把患者姓名与金额全带走，无审批无留痕——
-- 与客户第一条要求正好相反。因此本批次不只是「加审批」，而是先把数据出口收到后端来。
--
-- 三张表：
--   export_dataset —— 可导出数据集目录。**敏感级别与所需权限码是配置**，
--                     客户对「哪些算敏感」改口时改数据即可，不改 Java。
--   export_request —— 申请与审批。敏感类必须经他人批准才能下载。
--   export_audit   —— 每次实际下载的留痕：操作人、时间、导出范围、行数、字段清单。

-- ---------------------------------------------------------------------------
-- 1. 可导出数据集目录
-- ---------------------------------------------------------------------------

CREATE TABLE export_dataset (
    dataset_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    dataset_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    sensitivity VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    permission_code VARCHAR(96) NULL,
    field_list VARCHAR(1024) NOT NULL,
    description VARCHAR(512) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_export_dataset_code (dataset_code),
    KEY idx_export_dataset_sensitivity (sensitivity, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- sensitivity = SENSITIVE 的四类正是客户点名的「客户信息、地址、账单」与「价格」。
-- field_list 同时是留痕要记的「字段清单」，与实际导出的表头由测试断言保持一致。
INSERT INTO export_dataset
    (dataset_code, display_name, sensitivity, permission_code, field_list, description)
VALUES
    ('CUSTOMER_PROFILE', '客户档案', 'SENSITIVE', 'export:sensitive',
     '客户编号,客户名称,联系人,联系电话,联系邮箱,业务区域,业务员,客户类型,结算方式,状态',
     '客户信息属客户点名的敏感类，导出需批准。'),
    ('CUSTOMER_SHIPPING_ADDRESS', '客户收货地址', 'SENSITIVE', 'export:sensitive',
     '客户编号,客户名称,地址标签,收件人,收件电话,省,市,区,详细地址,是否默认',
     '地址属客户点名的敏感类，导出需批准。'),
    ('ORDER_BILL', '订单账单与金额', 'SENSITIVE', 'export:sensitive',
     '订单号,客户名称,账单号,账单状态,付款状态,金额,币种,创建时间',
     '账单属客户点名的敏感类，导出需批准。'),
    ('PRODUCT_PRICE', '产品价格', 'SENSITIVE', 'export:sensitive',
     '产品编码,产品名称,分类,报价状态,基础价格,币种,状态',
     '价格属客户点名的敏感类，导出需批准。'),
    ('ORDER_LIST', '订单列表', 'NORMAL', NULL,
     '订单号,客户名称,患者,产品类型,内部状态,对外状态,创建时间,更新时间',
     '不含金额与地址的订单台账，导出直接留痕。'),
    ('PRODUCTION_TASK', '生产工序任务', 'NORMAL', NULL,
     '订单号,工序编码,工序名称,节点状态,执行人,开始时间,完成时间',
     '生产工序台账，导出直接留痕。'),
    ('REWORK_RECORD', '返工记录', 'NORMAL', NULL,
     '返工单号,订单号,返工类型,责任方,返工状态,创建时间,关闭时间',
     '返工台账，导出直接留痕。');

-- ---------------------------------------------------------------------------
-- 2. 导出申请与审批
-- ---------------------------------------------------------------------------
-- sensitivity 在申请时冻结：之后管理端把某个数据集改成非敏感，不应让已经挂起的
-- 申请自动变成免审批。

CREATE TABLE export_request (
    export_request_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_no VARCHAR(64) NOT NULL,
    dataset_code VARCHAR(64) NOT NULL,
    sensitivity VARCHAR(16) NOT NULL,
    filter_json JSON NULL,
    reason VARCHAR(512) NULL,
    requested_by_user_id BIGINT NOT NULL,
    requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    approval_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    approved_by_user_id BIGINT NULL,
    approved_at DATETIME(3) NULL,
    approval_comment VARCHAR(512) NULL,
    download_count INT NOT NULL DEFAULT 0,
    last_downloaded_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_export_request_no (request_no),
    KEY idx_export_request_status (approval_status, requested_at),
    KEY idx_export_request_requester (requested_by_user_id, requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 3. 导出留痕
-- ---------------------------------------------------------------------------
-- 客户要求的五项：操作人、时间、导出范围、行数、字段清单，逐列落地。
-- 与 export_request 分开：一次批准可能下载多次，每次下载都要单独留痕。

CREATE TABLE export_audit (
    export_audit_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    export_request_id BIGINT NOT NULL,
    dataset_code VARCHAR(64) NOT NULL,
    sensitivity VARCHAR(16) NOT NULL,
    operator_user_id BIGINT NOT NULL,
    exported_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    filter_json JSON NULL,
    row_count INT NOT NULL,
    field_list VARCHAR(1024) NOT NULL,
    approved_by_user_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_export_audit_request (export_request_id, exported_at),
    KEY idx_export_audit_operator (operator_user_id, exported_at),
    KEY idx_export_audit_dataset (dataset_code, exported_at),
    CONSTRAINT fk_export_audit_request FOREIGN KEY (export_request_id)
        REFERENCES export_request (export_request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 4. 权限码
-- ---------------------------------------------------------------------------

INSERT INTO system_permission (permission_code, permission_name, module_code, status)
VALUES
    ('export:execute', '执行非敏感数据导出', 'export', 'ACTIVE'),
    ('export:sensitive', '申请敏感数据导出', 'export', 'ACTIVE'),
    ('export:approve', '审批敏感数据导出', 'export', 'ACTIVE'),
    ('export:audit:read', '查看导出留痕', 'export', 'ACTIVE')
ON DUPLICATE KEY UPDATE
    permission_name = VALUES(permission_name),
    module_code = VALUES(module_code),
    status = VALUES(status);

CREATE TEMPORARY TABLE tmp_export_grant (
    role_code VARCHAR(32) NOT NULL,
    permission_code VARCHAR(96) NOT NULL
) ENGINE=Memory DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 授权按 B 批次的口径就窄不就宽：宁可范围偏窄，也不要先给成全量再往回收。
--   * 医生端一个码都不给——客户第一条要求是「不允许医生直接导出」；
--   * 敏感类只有管理员与经理能申请，对应「只能由管理者账号导出」；
--   * 非敏感类各管理端都能导出，对应「各个管理端都需要数据导出」，全部留痕。
INSERT INTO tmp_export_grant (role_code, permission_code) VALUES
    ('ADMIN', 'export:execute'), ('ADMIN', 'export:sensitive'),
    ('ADMIN', 'export:approve'), ('ADMIN', 'export:audit:read'),
    ('ADMIN_MANAGER', 'export:execute'), ('ADMIN_MANAGER', 'export:sensitive'),
    ('ADMIN_MANAGER', 'export:approve'), ('ADMIN_MANAGER', 'export:audit:read'),
    -- 主管可以申请敏感导出但不能自己批，审批权只在管理员与经理手上。
    ('ADMIN_SUPERVISOR', 'export:execute'), ('ADMIN_SUPERVISOR', 'export:sensitive'),
    ('ADMIN_SUPERVISOR', 'export:audit:read'),
    ('ADMIN_STAFF', 'export:execute'),
    -- 客服端与生产端：入口角色与各自的经理 / 主管拿非敏感导出。
    ('CS', 'export:execute'),
    ('CS_MANAGER', 'export:execute'), ('CS_MANAGER', 'export:audit:read'),
    ('CS_SENIOR', 'export:execute'),
    ('CS_AGENT', 'export:execute'),
    ('WORKER', 'export:execute'),
    ('PROD_MANAGER', 'export:execute'), ('PROD_MANAGER', 'export:audit:read'),
    ('PROD_SUPERVISOR', 'export:execute');

INSERT INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM tmp_export_grant g
JOIN system_role r ON r.role_code = g.role_code
JOIN system_permission p ON p.permission_code = g.permission_code
WHERE NOT EXISTS (
    SELECT 1 FROM system_role_permission existing
    WHERE existing.role_id = r.role_id
      AND existing.permission_id = p.permission_id
);

DROP TEMPORARY TABLE tmp_export_grant;

-- ---------------------------------------------------------------------------
-- 5. 管理端菜单
-- ---------------------------------------------------------------------------
-- system_menu.menu_id 不是自增列，沿用手工分配的既有约定（见 V80 的说明）。

INSERT INTO system_menu
    (menu_id, parent_id, menu_code, menu_name, menu_type, route_path, permission_code, icon, sort_order, status)
VALUES
    (1860, NULL, 'admin-export-center', '数据导出', 'MENU', '/admin/export/center', 'export:execute', 'file', 940, 'ACTIVE'),
    (1870, NULL, 'admin-export-audit', '导出留痕', 'MENU', '/admin/export/audit', 'export:audit:read', 'quality', 950, 'ACTIVE')
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
  AND m.menu_code IN ('admin-export-center', 'admin-export-audit');
