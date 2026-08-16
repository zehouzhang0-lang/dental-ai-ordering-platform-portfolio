-- GOAL-033 / TASK-034 B 批次：客户确认的细分角色与专项权限落地为可配置数据。
--
-- 这些角色码不是 UserRole 枚举值。用户同时持有一个入口角色（DOCTOR/CS/WORKER/ADMIN，决定从哪个端登录
-- 和数据范围默认值）与一个或多个细分角色（决定实际能做什么）。A 批次已让 primaryRole 忽略
-- 无法映射为入口角色的角色码，因此这里新增角色不需要改任何 Java 代码。
--
-- 数据范围的过渡说明：客户对「高级客服=分配客户」「部门主管=本部门」的要求，需要 ASSIGNED / DEPT
-- 两档数据范围，分别依赖 GOAL-034 G4 的客户负责人关系与真实部门数据（客户未提供）。
-- 在那之前这些角色统一按 SELF 配置——宁可范围偏窄，也不要先给成 ALL 再往回收。

-- ---------------------------------------------------------------------------
-- 1. 专项权限码
-- ---------------------------------------------------------------------------

INSERT INTO system_permission (permission_code, permission_name, module_code, status)
VALUES
    ('check:gate-inspect', '执行入检与出检', 'check', 'ACTIVE'),
    ('check:sample-inspect', '执行过程抽检', 'check', 'ACTIVE'),
    ('rework:register-internal', '登记内返', 'rework', 'ACTIVE'),
    ('rework:confirm-responsibility', '确认返工责任', 'rework', 'ACTIVE'),
    ('logistics:receive', '登记收货', 'logistics', 'ACTIVE'),
    ('logistics:ship', '登记发货', 'logistics', 'ACTIVE'),
    ('message:translate', '处理翻译任务', 'message', 'ACTIVE'),
    ('production:review-data', '审核生产资料', 'production', 'ACTIVE')
ON DUPLICATE KEY UPDATE
    permission_name = VALUES(permission_name),
    module_code = VALUES(module_code),
    status = VALUES(status);

-- ---------------------------------------------------------------------------
-- 2. 细分角色
-- ---------------------------------------------------------------------------
-- 生产资料审核员：客户已勾「取消」，但承接方尚未确认（PROD-CLARIFY-03）。
-- 先以 INACTIVE 建档保留结构，由开关决定承接方，避免客户改口时重建。

INSERT INTO system_role (role_code, role_name, data_scope, status)
VALUES
    ('CLINIC_ADMIN', '诊所管理员', 'CLINIC', 'ACTIVE'),
    ('CLINIC_DOCTOR', '医生', 'SELF', 'ACTIVE'),
    ('CLINIC_FRONTDESK', '前台', 'CLINIC', 'ACTIVE'),
    ('CLINIC_ASSISTANT', '护士/助手', 'CLINIC', 'ACTIVE'),
    ('CS_MANAGER', '客服经理', 'ALL', 'ACTIVE'),
    ('CS_SENIOR', '高级客服', 'SELF', 'ACTIVE'),
    ('CS_AGENT', '普通客服', 'SELF', 'ACTIVE'),
    ('CS_TRANSLATOR', '翻译人员', 'SELF', 'ACTIVE'),
    ('CS_RECEIVER', '收货人员', 'SELF', 'ACTIVE'),
    ('CS_SHIPPER', '发货人员', 'SELF', 'ACTIVE'),
    ('PROD_MANAGER', '生产经理', 'ALL', 'ACTIVE'),
    ('PROD_SUPERVISOR', '部门/班组主管', 'SELF', 'ACTIVE'),
    ('PROD_TEAM_LEAD', '组长', 'SELF', 'ACTIVE'),
    ('PROD_TECHNICIAN', '技工', 'SELF', 'ACTIVE'),
    ('PROD_QC', '质检员', 'SELF', 'ACTIVE'),
    ('PROD_FINAL_QC', '终检员', 'SELF', 'ACTIVE'),
    ('PROD_DATA_REVIEWER', '生产资料审核员', 'SELF', 'INACTIVE'),
    ('ADMIN_MANAGER', '经理', 'ALL', 'ACTIVE'),
    ('ADMIN_SUPERVISOR', '主管', 'ALL', 'ACTIVE'),
    ('ADMIN_STAFF', '普通员工', 'SELF', 'ACTIVE')
ON DUPLICATE KEY UPDATE
    role_name = VALUES(role_name),
    data_scope = VALUES(data_scope);

-- ---------------------------------------------------------------------------
-- 3. 角色 → 权限码
-- ---------------------------------------------------------------------------

-- 必须显式声明字符集与排序规则：临时表默认跟随库级默认值，而开发库是 utf8mb4_0900_ai_ci、
-- 测试库是 utf8mb4_unicode_ci。不写死的话在开发库上会因为 "Illegal mix of collations" 失败，
-- 而测试库上却能通过——环境差异会让这类问题只在部署时才暴露。
CREATE TEMPORARY TABLE tmp_role_grant (
    role_code VARCHAR(32) NOT NULL,
    permission_code VARCHAR(96) NOT NULL
) ENGINE=Memory DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO tmp_role_grant (role_code, permission_code) VALUES
    -- 医生端
    ('CLINIC_ADMIN', 'order:read-doctor'), ('CLINIC_ADMIN', 'order:write-doctor'),
    ('CLINIC_ADMIN', 'patient:manage-doctor'), ('CLINIC_ADMIN', 'clinic:read-self'),
    ('CLINIC_ADMIN', 'account:doctor'), ('CLINIC_ADMIN', 'file:access-doctor'),
    ('CLINIC_ADMIN', 'ai:doctor'), ('CLINIC_ADMIN', 'catalog:read-doctor'),
    ('CLINIC_ADMIN', 'notification:read-self'), ('CLINIC_ADMIN', 'notification:write-self'),
    ('CLINIC_DOCTOR', 'order:read-doctor'), ('CLINIC_DOCTOR', 'order:write-doctor'),
    ('CLINIC_DOCTOR', 'patient:manage-doctor'), ('CLINIC_DOCTOR', 'file:access-doctor'),
    ('CLINIC_DOCTOR', 'ai:doctor'), ('CLINIC_DOCTOR', 'catalog:read-doctor'),
    ('CLINIC_DOCTOR', 'account:doctor'),
    ('CLINIC_DOCTOR', 'notification:read-self'), ('CLINIC_DOCTOR', 'notification:write-self'),
    ('CLINIC_FRONTDESK', 'order:read-doctor'), ('CLINIC_FRONTDESK', 'patient:manage-doctor'),
    ('CLINIC_FRONTDESK', 'clinic:read-self'), ('CLINIC_FRONTDESK', 'catalog:read-doctor'),
    ('CLINIC_FRONTDESK', 'notification:read-self'), ('CLINIC_FRONTDESK', 'notification:write-self'),
    ('CLINIC_ASSISTANT', 'order:read-doctor'), ('CLINIC_ASSISTANT', 'patient:manage-doctor'),
    ('CLINIC_ASSISTANT', 'file:access-doctor'), ('CLINIC_ASSISTANT', 'catalog:read-doctor'),
    ('CLINIC_ASSISTANT', 'notification:read-self'), ('CLINIC_ASSISTANT', 'notification:write-self'),
    -- 客服端
    ('CS_MANAGER', 'order:read-internal'), ('CS_MANAGER', 'order:read-case-group-internal'),
    ('CS_MANAGER', 'clinic:manage'), ('CS_MANAGER', 'clinic:read-internal'),
    ('CS_MANAGER', 'clinic:preference:write'), ('CS_MANAGER', 'clinic:price:manage'),
    ('CS_MANAGER', 'clinic:blacklist:manage'), ('CS_MANAGER', 'clinic:template:manage'),
    ('CS_MANAGER', 'message:manage'), ('CS_MANAGER', 'message:translate'),
    ('CS_MANAGER', 'design-draft:internal-review'), ('CS_MANAGER', 'design-task:read-progress'),
    ('CS_MANAGER', 'product:manage'), ('CS_MANAGER', 'dashboard:read-internal'),
    ('CS_MANAGER', 'dashboard:read-sales'), ('CS_MANAGER', 'staff:read-workload'),
    ('CS_MANAGER', 'quality:record:manage'), ('CS_MANAGER', 'quality:external-return:manage'),
    ('CS_MANAGER', 'workflow:operate-business-gate'), ('CS_MANAGER', 'workflow:read-internal'),
    ('CS_MANAGER', 'ai:cs'), ('CS_MANAGER', 'ai:governance:read'),
    ('CS_MANAGER', 'file:manage-internal'), ('CS_MANAGER', 'logistics:receive'),
    ('CS_MANAGER', 'logistics:ship'),
    ('CS_MANAGER', 'notification:read-self'), ('CS_MANAGER', 'notification:write-self'),
    ('CS_SENIOR', 'order:read-internal'), ('CS_SENIOR', 'order:read-case-group-internal'),
    ('CS_SENIOR', 'clinic:read-internal'), ('CS_SENIOR', 'clinic:preference:write'),
    ('CS_SENIOR', 'message:manage'), ('CS_SENIOR', 'message:translate'),
    ('CS_SENIOR', 'design-task:read-progress'), ('CS_SENIOR', 'workflow:read-internal'),
    ('CS_SENIOR', 'workflow:operate-business-gate'), ('CS_SENIOR', 'ai:cs'),
    ('CS_SENIOR', 'quality:external-return:manage'), ('CS_SENIOR', 'dashboard:read-internal'),
    ('CS_SENIOR', 'file:manage-internal'),
    ('CS_SENIOR', 'notification:read-self'), ('CS_SENIOR', 'notification:write-self'),
    ('CS_AGENT', 'order:read-internal'), ('CS_AGENT', 'order:read-case-group-internal'),
    ('CS_AGENT', 'clinic:read-internal'), ('CS_AGENT', 'message:manage'),
    ('CS_AGENT', 'design-task:read-progress'), ('CS_AGENT', 'workflow:read-internal'),
    ('CS_AGENT', 'ai:cs'), ('CS_AGENT', 'dashboard:read-internal'),
    ('CS_AGENT', 'notification:read-self'), ('CS_AGENT', 'notification:write-self'),
    ('CS_TRANSLATOR', 'order:read-internal'), ('CS_TRANSLATOR', 'message:translate'),
    ('CS_TRANSLATOR', 'ai:cs'),
    ('CS_TRANSLATOR', 'notification:read-self'), ('CS_TRANSLATOR', 'notification:write-self'),
    -- 收货人员：只登记收货，不碰订单业务与客户资料
    ('CS_RECEIVER', 'order:read-internal'), ('CS_RECEIVER', 'logistics:receive'),
    ('CS_RECEIVER', 'notification:read-self'), ('CS_RECEIVER', 'notification:write-self'),
    -- 发货人员：只登记发货
    ('CS_SHIPPER', 'order:read-internal'), ('CS_SHIPPER', 'logistics:ship'),
    ('CS_SHIPPER', 'notification:read-self'), ('CS_SHIPPER', 'notification:write-self'),
    -- 生产端
    ('PROD_MANAGER', 'workflow:read-internal'), ('PROD_MANAGER', 'workflow:assign'),
    ('PROD_MANAGER', 'workflow:review-production'), ('PROD_MANAGER', 'workflow:skip-optional'),
    ('PROD_MANAGER', 'check:read-internal'), ('PROD_MANAGER', 'dashboard:read-internal'),
    ('PROD_MANAGER', 'performance:read-all'), ('PROD_MANAGER', 'staff:read-workload'),
    ('PROD_MANAGER', 'file:manage-internal'), ('PROD_MANAGER', 'ai:production'),
    ('PROD_MANAGER', 'production:equipment:write'), ('PROD_MANAGER', 'production:material:write'),
    ('PROD_MANAGER', 'production:safety:write'), ('PROD_MANAGER', 'production:cost:write'),
    ('PROD_MANAGER', 'production:reward-penalty:write'),
    ('PROD_MANAGER', 'notification:read-self'), ('PROD_MANAGER', 'notification:write-self'),
    ('PROD_SUPERVISOR', 'workflow:read-internal'), ('PROD_SUPERVISOR', 'workflow:assign'),
    ('PROD_SUPERVISOR', 'check:read-internal'), ('PROD_SUPERVISOR', 'check:gate-inspect'),
    ('PROD_SUPERVISOR', 'rework:register-internal'), ('PROD_SUPERVISOR', 'dashboard:read-internal'),
    ('PROD_SUPERVISOR', 'file:manage-internal'), ('PROD_SUPERVISOR', 'ai:production'),
    ('PROD_SUPERVISOR', 'production:material:write'), ('PROD_SUPERVISOR', 'production:safety:write'),
    ('PROD_SUPERVISOR', 'notification:read-self'), ('PROD_SUPERVISOR', 'notification:write-self'),
    -- 组长：入检/出检的检查人；内返登记人
    ('PROD_TEAM_LEAD', 'workflow:read-internal'), ('PROD_TEAM_LEAD', 'workflow:operate-assigned'),
    ('PROD_TEAM_LEAD', 'check:read-internal'), ('PROD_TEAM_LEAD', 'check:write'),
    ('PROD_TEAM_LEAD', 'check:gate-inspect'), ('PROD_TEAM_LEAD', 'rework:register-internal'),
    ('PROD_TEAM_LEAD', 'worklog:write-self'), ('PROD_TEAM_LEAD', 'performance:read-self'),
    ('PROD_TEAM_LEAD', 'file:manage-internal'), ('PROD_TEAM_LEAD', 'ai:production'),
    ('PROD_TEAM_LEAD', 'notification:read-self'), ('PROD_TEAM_LEAD', 'notification:write-self'),
    ('PROD_TECHNICIAN', 'workflow:read-internal'), ('PROD_TECHNICIAN', 'workflow:operate-assigned'),
    ('PROD_TECHNICIAN', 'worklog:write-self'), ('PROD_TECHNICIAN', 'performance:read-self'),
    ('PROD_TECHNICIAN', 'check:read-internal'), ('PROD_TECHNICIAN', 'file:manage-internal'),
    ('PROD_TECHNICIAN', 'design-task:claim'), ('PROD_TECHNICIAN', 'design-task:operate-self'),
    ('PROD_TECHNICIAN', 'ai:production'),
    ('PROD_TECHNICIAN', 'notification:read-self'), ('PROD_TECHNICIAN', 'notification:write-self'),
    -- 质检员：只做过程抽检与责任确认，不做入检/出检
    ('PROD_QC', 'workflow:read-internal'), ('PROD_QC', 'workflow:operate-assigned'),
    ('PROD_QC', 'check:read-internal'), ('PROD_QC', 'check:write'),
    ('PROD_QC', 'check:sample-inspect'), ('PROD_QC', 'rework:confirm-responsibility'),
    ('PROD_QC', 'performance:read-self'), ('PROD_QC', 'file:manage-internal'),
    ('PROD_QC', 'notification:read-self'), ('PROD_QC', 'notification:write-self'),
    ('PROD_FINAL_QC', 'workflow:read-internal'), ('PROD_FINAL_QC', 'workflow:operate-assigned'),
    ('PROD_FINAL_QC', 'check:read-internal'), ('PROD_FINAL_QC', 'check:write'),
    ('PROD_FINAL_QC', 'check:gate-inspect'), ('PROD_FINAL_QC', 'final-inspection:manage'),
    ('PROD_FINAL_QC', 'performance:read-self'), ('PROD_FINAL_QC', 'file:manage-internal'),
    ('PROD_FINAL_QC', 'notification:read-self'), ('PROD_FINAL_QC', 'notification:write-self'),
    ('PROD_DATA_REVIEWER', 'workflow:read-internal'), ('PROD_DATA_REVIEWER', 'production:review-data'),
    ('PROD_DATA_REVIEWER', 'workflow:review-production'), ('PROD_DATA_REVIEWER', 'check:read-internal'),
    ('PROD_DATA_REVIEWER', 'notification:read-self'), ('PROD_DATA_REVIEWER', 'notification:write-self'),
    -- 管理端
    ('ADMIN_MANAGER', 'order:read-internal'), ('ADMIN_MANAGER', 'order:read-case-group-internal'),
    ('ADMIN_MANAGER', 'workflow:read-internal'), ('ADMIN_MANAGER', 'workflow:assign'),
    ('ADMIN_MANAGER', 'workflow:review-production'), ('ADMIN_MANAGER', 'check:read-internal'),
    ('ADMIN_MANAGER', 'dashboard:read-internal'), ('ADMIN_MANAGER', 'dashboard:read-sales'),
    ('ADMIN_MANAGER', 'performance:read-all'), ('ADMIN_MANAGER', 'staff:manage'),
    ('ADMIN_MANAGER', 'staff:read-workload'), ('ADMIN_MANAGER', 'clinic:manage'),
    ('ADMIN_MANAGER', 'clinic:read-internal'), ('ADMIN_MANAGER', 'quality:record:manage'),
    ('ADMIN_MANAGER', 'ai:governance:read'), ('ADMIN_MANAGER', 'file:manage-internal'),
    ('ADMIN_MANAGER', 'notification:read-self'), ('ADMIN_MANAGER', 'notification:write-self'),
    ('ADMIN_SUPERVISOR', 'order:read-internal'), ('ADMIN_SUPERVISOR', 'workflow:read-internal'),
    ('ADMIN_SUPERVISOR', 'check:read-internal'), ('ADMIN_SUPERVISOR', 'dashboard:read-internal'),
    ('ADMIN_SUPERVISOR', 'staff:read-workload'), ('ADMIN_SUPERVISOR', 'clinic:read-internal'),
    ('ADMIN_SUPERVISOR', 'file:manage-internal'),
    ('ADMIN_SUPERVISOR', 'notification:read-self'), ('ADMIN_SUPERVISOR', 'notification:write-self'),
    ('ADMIN_STAFF', 'order:read-internal'), ('ADMIN_STAFF', 'dashboard:read-internal'),
    ('ADMIN_STAFF', 'notification:read-self'), ('ADMIN_STAFF', 'notification:write-self');

INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM tmp_role_grant g
JOIN system_role r ON r.role_code = g.role_code
JOIN system_permission p ON p.permission_code = g.permission_code;

DROP TEMPORARY TABLE tmp_role_grant;

-- 四个入口角色也要拿到新增的专项权限码，否则现有演示账号会失去既有能力。
INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p
WHERE r.role_code = 'WORKER'
  AND p.permission_code IN ('check:gate-inspect', 'check:sample-inspect',
                            'rework:register-internal', 'rework:confirm-responsibility');

INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p
WHERE r.role_code = 'CS'
  AND p.permission_code IN ('logistics:receive', 'logistics:ship', 'message:translate');

INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p
WHERE r.role_code = 'ADMIN'
  AND p.permission_code IN ('check:gate-inspect', 'check:sample-inspect',
                            'rework:register-internal', 'rework:confirm-responsibility',
                            'logistics:receive', 'logistics:ship', 'message:translate',
                            'production:review-data');

-- ---------------------------------------------------------------------------
-- 4. 待客户澄清项做成配置开关，不写死
-- ---------------------------------------------------------------------------

CREATE TABLE system_config (
    config_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_key VARCHAR(128) NOT NULL,
    config_value VARCHAR(255) NOT NULL,
    description VARCHAR(512) NULL,
    updated_by_user_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_system_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO system_config (config_key, config_value, description) VALUES
    ('role.cs-senior.enabled', 'true',
     '高级客服是否保留。客户确认表中「保留」与「取消」两个框都打了勾，待澄清（CS-CLARIFY-01）。'),
    ('role.admin.can-operate-production', 'false',
     '管理端能否代操作生产。客户否决了「不能代操作」的建议但未写允许到什么程度，待澄清（PROD-CLARIFY-02）。'
     ' 置 false 时管理员不能代技工开工/完工，仅保留派工与监督。'),
    ('role.production-data-reviewer.successor', 'PROD_SUPERVISOR',
     '生产资料审核员取消后由谁承接。客户已勾取消但未指定承接方，待澄清（PROD-CLARIFY-03）。'
     ' 可选值：PROD_SUPERVISOR / PROD_MANAGER / PROD_DATA_REVIEWER（后者表示恢复该角色）。');

-- ---------------------------------------------------------------------------
-- 5. 终检不合格退回负责部门组长
-- ---------------------------------------------------------------------------

ALTER TABLE rework_record
    ADD COLUMN routed_dept_id BIGINT NULL AFTER responsibility_type,
    ADD COLUMN routed_to_user_id BIGINT NULL AFTER routed_dept_id,
    ADD KEY idx_rework_record_routed (routed_to_user_id, status);
