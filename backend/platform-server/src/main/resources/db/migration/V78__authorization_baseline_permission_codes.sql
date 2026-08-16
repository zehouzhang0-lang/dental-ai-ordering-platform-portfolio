-- GOAL-033 / TASK-034 A 批次：授权底座统一。
--
-- 两点与 GOAL-033「调研结论三」的表述不同，以本迁移为准：
--   1. data_scope 本来就在 system_role 上，不需要「提升」；缺的是用户级覆盖列，本迁移补上。
--   2. 真正让角色级 data_scope 失效的是 DatabaseAuthService.resolveDataScope 里
--      「primaryRole 为 ADMIN/CS 就直接返回 ALL」的短路，属代码问题，由本批次一并修正。
--
-- 本迁移只补齐权限码与授权关系，不改变任何现有用户当前可访问的范围：
-- 新增权限码授予的角色集合，与它们替换掉的角色白名单完全一致。

ALTER TABLE system_user
    ADD COLUMN data_scope VARCHAR(32) NULL AFTER user_type;

INSERT INTO system_permission (permission_code, permission_name, module_code, status)
VALUES
    ('dashboard:read-internal', '读取内部经营看板', 'dashboard', 'ACTIVE'),
    ('dashboard:read-sales', '读取销售金额看板', 'dashboard', 'ACTIVE'),
    ('ai:governance:read', '读取 AI 治理与告警', 'ai', 'ACTIVE'),
    ('quality:record:manage', '维护质量记录状态', 'quality', 'ACTIVE'),
    ('quality:external-return:manage', '登记外返质量记录', 'quality', 'ACTIVE'),
    ('catalog:read-doctor', '读取医生端可下单产品', 'catalog', 'ACTIVE'),
    ('staff:read-workload', '读取人员工作量', 'staff', 'ACTIVE'),
    ('order:read-case-group-internal', '读取内部病例订单组', 'order', 'ACTIVE'),
    ('production:equipment:write', '维护设备台账', 'production', 'ACTIVE'),
    ('production:equipment:approve', '审批设备维护', 'production', 'ACTIVE'),
    ('production:material:write', '维护物料异常记录', 'production', 'ACTIVE'),
    ('production:safety:write', '维护安环记录', 'production', 'ACTIVE'),
    ('production:cost:write', '维护成本记录', 'production', 'ACTIVE'),
    ('production:cost:confirm', '确认成本记录', 'production', 'ACTIVE'),
    ('production:reward-penalty:write', '维护奖惩记录', 'production', 'ACTIVE')
ON DUPLICATE KEY UPDATE
    permission_name = VALUES(permission_name),
    module_code = VALUES(module_code),
    status = VALUES(status);

-- ADMIN：全部新增权限码。
INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p
WHERE r.role_code = 'ADMIN'
  AND p.permission_code IN (
      'dashboard:read-internal',
      'dashboard:read-sales',
      'ai:governance:read',
      'quality:record:manage',
      'quality:external-return:manage',
      'staff:read-workload',
      'order:read-case-group-internal',
      'production:equipment:write',
      'production:equipment:approve',
      'production:material:write',
      'production:safety:write',
      'production:cost:write',
      'production:cost:confirm',
      'production:reward-penalty:write'
  );

-- CS：替换掉的白名单里包含 CS 的项。
INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p
WHERE r.role_code = 'CS'
  AND p.permission_code IN (
      'dashboard:read-internal',
      'dashboard:read-sales',
      'ai:governance:read',
      'quality:record:manage',
      'quality:external-return:manage',
      'staff:read-workload',
      'order:read-case-group-internal'
  );

-- WORKER：生产现场台账写入与内部看板读取。
INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p
WHERE r.role_code = 'WORKER'
  AND p.permission_code IN (
      'dashboard:read-internal',
      'production:equipment:write',
      'production:material:write',
      'production:safety:write',
      'production:cost:write',
      'production:reward-penalty:write'
  );

-- DOCTOR：医生端产品目录读取。
INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p
WHERE r.role_code = 'DOCTOR'
  AND p.permission_code = 'catalog:read-doctor';

-- 既有权限码的授权补齐：这些码原先由「角色白名单」兜底，改为纯权限码判定后必须显式授予，
-- 否则会缩小现有用户的可访问范围。
INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p
WHERE r.role_code = 'CS'
  AND p.permission_code IN ('ai:cs', 'workflow:operate-business-gate', 'product:manage', 'clinic:manage');

INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p
WHERE r.role_code = 'WORKER'
  AND p.permission_code IN ('ai:production', 'check:read-internal', 'workflow:operate-assigned',
                            'workflow:read-internal', 'performance:read-self');

INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p
WHERE r.role_code = 'DOCTOR'
  AND p.permission_code IN ('ai:doctor', 'order:read-doctor', 'order:write-doctor');

-- 纠正一处既有的授权与实现不一致：workflow:assign 被授予了 CS，
-- 但派工接口的注解是 roles = ADMIN、服务层判定也是 ADMIN-only，CS 实际上一直被服务层挡住。
-- 改成纯权限码判定后，这条多余的授权会真的放开 CS 派工，与 PRD 11.3-03「非管理员不能修改工序链」冲突，故撤销。
DELETE rp
FROM system_role_permission rp
JOIN system_role r ON r.role_id = rp.role_id
JOIN system_permission p ON p.permission_id = rp.permission_id
WHERE r.role_code = 'CS'
  AND p.permission_code = 'workflow:assign';

-- 同类问题：ADMIN 被授予了一批医生端专属权限码，但这些接口的注解都是 roles = DOCTOR、
-- 服务层也用 requireDoctorOnly 挡住了管理端，实际从未生效。改成纯权限码判定后它们会真的放开
-- 管理端代医生下单、代确认收货、代管理患者，与 GOAL-033 的入口角色映射表冲突，故撤销。
-- 这些接口中同时接受 ADMIN 的（沟通、设计、正畸、诊所、文件），注解里都另有一个内部权限码，
-- ADMIN 通过内部码继续可访问，可见范围不变。
DELETE rp
FROM system_role_permission rp
JOIN system_role r ON r.role_id = rp.role_id
JOIN system_permission p ON p.permission_id = rp.permission_id
WHERE r.role_code = 'ADMIN'
  AND p.permission_code IN (
      'order:read-doctor',
      'ai:doctor',
      'patient:manage-doctor',
      'account:doctor',
      'clinic:read-self',
      'file:access-doctor'
  );
