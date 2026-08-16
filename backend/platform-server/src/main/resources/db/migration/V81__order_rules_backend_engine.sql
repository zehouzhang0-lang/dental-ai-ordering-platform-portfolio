-- GOAL-033 / TASK-034 F 批次：下单规则后端化。
--
-- 现状（GOAL-033 调研结论五）：try_in_required、过程确认、订单周期、运输类型在
-- DoctorCaseGroupWizard.vue 里医生能勾选，后端检索命中 0；后端完全没有交期计算逻辑。
-- 表现是「前端能选、后端不认」——演示时看起来功能齐全，实际不计价、不排期、不延长工期。
--
-- 本迁移建立三样东西：
--   1. ordering_rule_config —— 交期规则引擎的**数据**。各产品标准制作周期属客户未提供项（CP），
--      因此每条规则带 confirmation_status：PLACEHOLDER 的值必须在界面上标注为「待确认」，
--      不得表现为正式承诺交期。客户资料到位后改这张表即可，不需要改 Java。
--   2. order_delivery_plan / order_process_confirmation / order_try_in —— 每张订单的规则后果。
--   3. order_bill_item —— 计价项。试戴是独立计价项，落在这里；账单总额仍由客服在 order_bill 上出具。
--
-- 注意：**没有新增 InternalOrderStatus / ExternalOrderStatus 值**。
-- 「等待医生确认」「试戴中」是各自域的状态，与订单状态三层是不同的域
-- （见 docs/development/status-vocabulary.md 开篇「三个层次，不要混用」）。
-- 把它们塞进订单状态枚举会让一期验收的「13 个内部值 / 7 个外部值」口径失效。

-- ---------------------------------------------------------------------------
-- 1. 交期规则配置
-- ---------------------------------------------------------------------------

CREATE TABLE ordering_rule_config (
    rule_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_type VARCHAR(32) NOT NULL,
    rule_key VARCHAR(64) NOT NULL,
    numeric_value INT NOT NULL,
    value_unit VARCHAR(16) NOT NULL DEFAULT 'DAYS',
    confirmation_status VARCHAR(16) NOT NULL DEFAULT 'PLACEHOLDER',
    display_name VARCHAR(128) NOT NULL,
    description VARCHAR(512) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    updated_by_user_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_ordering_rule_config (rule_type, rule_key),
    KEY idx_ordering_rule_config_type (rule_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 各产品标准制作周期：客户未提供，全部是占位值（CP 项）。
-- rule_key 用 workflow_product_type；__DEFAULT__ 是找不到对应产品类型时的兜底。
INSERT INTO ordering_rule_config
    (rule_type, rule_key, numeric_value, value_unit, confirmation_status, display_name, description)
VALUES
    ('PRODUCT_CYCLE', 'REGULAR_CROWN', 5, 'DAYS', 'PLACEHOLDER', '常规冠修复标准制作周期', '占位值，待客户提供真实周期'),
    ('PRODUCT_CYCLE', 'VENEER_RESTORATION', 5, 'DAYS', 'PLACEHOLDER', '贴面修复标准制作周期', '占位值，待客户提供真实周期'),
    ('PRODUCT_CYCLE', 'TELESCOPIC_CROWN', 8, 'DAYS', 'PLACEHOLDER', '套筒冠标准制作周期', '占位值，待客户提供真实周期'),
    ('PRODUCT_CYCLE', 'IMPLANT_RESTORATION', 7, 'DAYS', 'PLACEHOLDER', '种植类修复标准制作周期', '占位值，待客户提供真实周期'),
    ('PRODUCT_CYCLE', 'PRECISION_ATTACHMENT', 8, 'DAYS', 'PLACEHOLDER', '精密附件标准制作周期', '占位值，待客户提供真实周期'),
    ('PRODUCT_CYCLE', 'REMOVABLE_STEEL', 8, 'DAYS', 'PLACEHOLDER', '活动件-钢托标准制作周期', '占位值，待客户提供真实周期'),
    ('PRODUCT_CYCLE', 'REMOVABLE_ACRYLIC', 6, 'DAYS', 'PLACEHOLDER', '活动件-胶托标准制作周期', '占位值，待客户提供真实周期'),
    ('PRODUCT_CYCLE', 'REMOVABLE_INVISIBLE', 6, 'DAYS', 'PLACEHOLDER', '活动件-隐形标准制作周期', '占位值，待客户提供真实周期'),
    ('PRODUCT_CYCLE', 'ORTHODONTICS', 10, 'DAYS', 'PLACEHOLDER', '正畸标准制作周期', '占位值，待客户提供真实周期'),
    ('PRODUCT_CYCLE', 'DESIGN_SERVICE', 2, 'DAYS', 'PLACEHOLDER', '设计服务标准交付周期', '占位值，待客户提供真实周期'),
    ('PRODUCT_CYCLE', '__DEFAULT__', 7, 'DAYS', 'PLACEHOLDER', '未配置产品类型的兜底周期', '占位值；新增产品类型未配周期时按此计算，不阻塞下单'),

    -- 订单周期（客户口径「订单类型」）：正常 / 3 天加急 / 当天出货。
    -- numeric_value 是**制作天数上限**，-1 表示不设上限（按产品标准周期）。
    -- 客户在《动态下单表最终版》里写死了 3 天与当天，因此这两条是 CONFIRMED。
    ('PRIORITY_CAP', 'NORMAL', -1, 'DAYS', 'CONFIRMED', '正常出货周期', '不设上限，按产品标准制作周期'),
    ('PRIORITY_CAP', 'RUSH_3_DAYS', 3, 'DAYS', 'CONFIRMED', '3 天加急', '制作天数上限 3 天'),
    ('PRIORITY_CAP', 'SAME_DAY', 0, 'DAYS', 'CONFIRMED', '当天出货', '制作天数上限 0 天，当天完成出货'),

    -- 过程确认：客户明确「每增加一项过程确认，交期 +1 天」，因此 CONFIRMED。
    ('PROCESS_CONFIRMATION', 'PER_ITEM_DAYS', 1, 'DAYS', 'CONFIRMED', '每项过程确认追加天数', '客户确认口径：每增加一项确认环节交期 +1 天'),
    -- 医生确认宽限期：客户只写了「长时间未确认」，没写几天，因此是占位值。
    ('PROCESS_CONFIRMATION', 'DOCTOR_GRACE_DAYS', 2, 'DAYS', 'PLACEHOLDER', '医生确认宽限天数', '占位值；超出后订单进入等待状态并按超时天数延后交期'),

    -- 运输类型影响到货时间（制作完成 → 到货之间的在途天数）。
    ('SHIPPING_TRANSIT', 'COURIER', 2, 'DAYS', 'PLACEHOLDER', '快递在途天数', '占位值，待客户提供真实时效'),
    ('SHIPPING_TRANSIT', 'SALES_DELIVERY', 1, 'DAYS', 'PLACEHOLDER', '业务员配送在途天数', '占位值，待客户提供真实时效'),
    ('SHIPPING_TRANSIT', 'SELF_PICKUP', 0, 'DAYS', 'CONFIRMED', '自取在途天数', '自取无在途时间');

-- ---------------------------------------------------------------------------
-- 2. 每张订单的交期计划
-- ---------------------------------------------------------------------------
-- 术语对照（任务书 / 客户确认表 / 前端字段三者用词不一致，此处以数据列为准）：
--   priority_code   ← 前端 case_priority，界面标签「订单周期」，任务书称「订单类型」
--   order_type      ← 前端 order_type，界面标签「订单类型」，任务书称「产品类型」
-- 前端字段名保持不变，避免为改口径而动 18k 行的下单向导。

CREATE TABLE order_delivery_plan (
    plan_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    order_type VARCHAR(32) NOT NULL DEFAULT 'ONLINE',
    priority_code VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    shipping_method VARCHAR(32) NOT NULL DEFAULT 'COURIER',
    inbound_tracking_no VARCHAR(128) NULL,
    baseline_date DATE NOT NULL,
    base_cycle_days INT NOT NULL,
    priority_cap_days INT NOT NULL,
    process_confirmation_count INT NOT NULL DEFAULT 0,
    process_confirmation_days INT NOT NULL DEFAULT 0,
    waiting_days INT NOT NULL DEFAULT 0,
    production_days INT NOT NULL,
    transit_days INT NOT NULL,
    computed_delivery_date DATE NOT NULL,
    doctor_requested_delivery_date DATE NULL,
    variance_days INT NULL,
    variance_flag VARCHAR(32) NOT NULL DEFAULT 'NONE',
    estimate_status VARCHAR(16) NOT NULL DEFAULT 'PLACEHOLDER',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_order_delivery_plan_order (order_id),
    KEY idx_order_delivery_plan_variance (variance_flag, computed_delivery_date),
    CONSTRAINT fk_order_delivery_plan_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 3. 过程确认
-- ---------------------------------------------------------------------------
-- confirmation_status 是**过程确认域**的状态，与订单状态、设计稿状态都不是一个域：
--   PLANNED         下单时勾选，尚未到达该环节
--   AWAITING_DOCTOR 内部已发起确认请求，等待医生
--   CONFIRMED       医生已确认
--   REJECTED        医生要求修改

CREATE TABLE order_process_confirmation (
    confirmation_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    confirmation_code VARCHAR(64) NOT NULL,
    confirmation_name VARCHAR(128) NOT NULL,
    sequence_no INT NOT NULL,
    confirmation_status VARCHAR(32) NOT NULL DEFAULT 'PLANNED',
    requested_at DATETIME(3) NULL,
    requested_by_user_id BIGINT NULL,
    responded_at DATETIME(3) NULL,
    responded_by_user_id BIGINT NULL,
    doctor_comment VARCHAR(512) NULL,
    settled_waiting_days INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_order_process_confirmation (order_id, confirmation_code),
    KEY idx_order_process_confirmation_status (confirmation_status, requested_at),
    CONSTRAINT fk_order_process_confirmation_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 4. 试戴
-- ---------------------------------------------------------------------------
-- 客户口径：「试戴作为独立计价项落入账单；试戴完成后同一订单可继续选择成品与材料，不新建订单。」
-- 因此 finalize 走的是**同一个 order_id**，不产生新订单号。

CREATE TABLE order_try_in (
    try_in_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    try_in_status VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    completed_at DATETIME(3) NULL,
    completed_by_user_id BIGINT NULL,
    finalized_at DATETIME(3) NULL,
    finalized_by_user_id BIGINT NULL,
    finalize_note VARCHAR(512) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_order_try_in_order (order_id),
    KEY idx_order_try_in_status (try_in_status),
    CONSTRAINT fk_order_try_in_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 5. 计价项
-- ---------------------------------------------------------------------------
-- order_bill 是客服上传的账单 PDF 与总额，语义不变。order_bill_item 是**系统按下单规则生成的计价项**，
-- 供客服核价时对照。价格属客户未提供项，因此绝大多数项落 PENDING_QUOTE，amount_cents 留空。

CREATE TABLE order_bill_item (
    bill_item_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    item_code VARCHAR(64) NOT NULL,
    item_name VARCHAR(128) NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    pricing_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_QUOTE',
    amount_cents BIGINT NULL,
    currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
    source_type VARCHAR(32) NOT NULL DEFAULT 'ORDER_SUBMIT',
    remark VARCHAR(255) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_order_bill_item (order_id, item_code),
    KEY idx_order_bill_item_order (order_id, sort_order),
    CONSTRAINT fk_order_bill_item_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 6. 权限码
-- ---------------------------------------------------------------------------
-- 与 A 批次口径一致：授权按接口注解与服务层实际判定核对，不多授。

INSERT INTO system_permission (permission_code, permission_name, module_code, status)
VALUES
    ('order:process-confirm-request', '发起过程确认请求', 'order-rules', 'ACTIVE'),
    ('order:process-confirm-doctor', '医生确认过程环节', 'order-rules', 'ACTIVE'),
    ('order:try-in-manage', '登记试戴完成', 'order-rules', 'ACTIVE'),
    ('ordering-rule:manage', '维护下单规则与标准周期配置', 'order-rules', 'ACTIVE')
ON DUPLICATE KEY UPDATE
    permission_name = VALUES(permission_name),
    module_code = VALUES(module_code),
    status = VALUES(status);

CREATE TEMPORARY TABLE tmp_order_rule_grant (
    role_code VARCHAR(32) NOT NULL,
    permission_code VARCHAR(96) NOT NULL
) ENGINE=Memory DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO tmp_order_rule_grant (role_code, permission_code) VALUES
    -- 入口角色：保持 bootstrap header 链路（测试与演示）可用，口径与既有入口角色授权一致。
    ('ADMIN', 'order:process-confirm-request'), ('ADMIN', 'order:try-in-manage'),
    ('ADMIN', 'ordering-rule:manage'),
    ('CS', 'order:process-confirm-request'), ('CS', 'order:try-in-manage'),
    ('WORKER', 'order:process-confirm-request'),
    ('DOCTOR', 'order:process-confirm-doctor'),
    -- 医生端细分角色：能下单的才能确认过程环节；前台与护士助手不下单，也不确认。
    ('CLINIC_ADMIN', 'order:process-confirm-doctor'),
    ('CLINIC_DOCTOR', 'order:process-confirm-doctor'),
    -- 客服端：受理订单的角色可以发起确认请求并登记试戴完成。
    ('CS_MANAGER', 'order:process-confirm-request'), ('CS_MANAGER', 'order:try-in-manage'),
    ('CS_SENIOR', 'order:process-confirm-request'), ('CS_SENIOR', 'order:try-in-manage'),
    ('CS_AGENT', 'order:process-confirm-request'), ('CS_AGENT', 'order:try-in-manage'),
    -- 生产端：制作过程中的确认环节由生产侧发起。
    ('PROD_MANAGER', 'order:process-confirm-request'),
    ('PROD_SUPERVISOR', 'order:process-confirm-request'),
    ('PROD_TEAM_LEAD', 'order:process-confirm-request'),
    -- 管理端：标准周期与规则配置由管理端维护（占位值转正的入口）。
    ('ADMIN_MANAGER', 'ordering-rule:manage'),
    ('ADMIN_SUPERVISOR', 'ordering-rule:manage');

INSERT INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM tmp_order_rule_grant g
JOIN system_role r ON r.role_code = g.role_code
JOIN system_permission p ON p.permission_code = g.permission_code
WHERE NOT EXISTS (
    SELECT 1 FROM system_role_permission existing
    WHERE existing.role_id = r.role_id
      AND existing.permission_id = p.permission_id
);

DROP TEMPORARY TABLE tmp_order_rule_grant;
