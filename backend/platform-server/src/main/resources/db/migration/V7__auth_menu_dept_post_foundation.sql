CREATE TABLE system_dept (
    dept_id BIGINT PRIMARY KEY,
    parent_id BIGINT NULL,
    dept_name VARCHAR(64) NOT NULL,
    dept_code VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_system_dept_code (dept_code),
    KEY idx_system_dept_parent (parent_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE system_post (
    post_id BIGINT PRIMARY KEY,
    post_code VARCHAR(64) NOT NULL,
    post_name VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_system_post_code (post_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE system_user_post (
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, post_id),
    CONSTRAINT fk_system_user_post_user FOREIGN KEY (user_id) REFERENCES system_user (user_id),
    CONSTRAINT fk_system_user_post_post FOREIGN KEY (post_id) REFERENCES system_post (post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE system_menu (
    menu_id BIGINT PRIMARY KEY,
    parent_id BIGINT NULL,
    menu_code VARCHAR(96) NOT NULL,
    menu_name VARCHAR(96) NOT NULL,
    menu_type VARCHAR(32) NOT NULL,
    route_path VARCHAR(128) NULL,
    component_path VARCHAR(128) NULL,
    permission_code VARCHAR(96) NULL,
    icon VARCHAR(64) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_system_menu_code (menu_code),
    KEY idx_system_menu_parent (parent_id, status),
    KEY idx_system_menu_permission (permission_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE system_role_menu (
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (role_id, menu_id),
    CONSTRAINT fk_system_role_menu_role FOREIGN KEY (role_id) REFERENCES system_role (role_id),
    CONSTRAINT fk_system_role_menu_menu FOREIGN KEY (menu_id) REFERENCES system_menu (menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE system_user
    ADD COLUMN dept_id BIGINT NULL AFTER clinic_id,
    ADD KEY idx_system_user_dept (dept_id, status),
    ADD CONSTRAINT fk_system_user_dept FOREIGN KEY (dept_id) REFERENCES system_dept (dept_id);

INSERT INTO system_dept (dept_id, parent_id, dept_name, dept_code, sort_order, status)
VALUES
    (100, NULL, '牙科定制工厂', 'factory', 1, 'ACTIVE'),
    (110, 100, '客服中心', 'customer-service', 10, 'ACTIVE'),
    (120, 100, '生产中心', 'production', 20, 'ACTIVE'),
    (130, 100, '管理中心', 'admin-office', 30, 'ACTIVE'),
    (200, NULL, '作品集虚构诊所', 'portfolio-clinic', 40, 'ACTIVE');

INSERT INTO system_post (post_id, post_code, post_name, sort_order, status)
VALUES
    (1001, 'ADMIN_MANAGER', '系统管理员', 1, 'ACTIVE'),
    (1002, 'CUSTOMER_SERVICE', '客服', 10, 'ACTIVE'),
    (1003, 'PRODUCTION_WORKER', '生产员工', 20, 'ACTIVE'),
    (1004, 'CLINIC_DOCTOR', '医生', 30, 'ACTIVE');

UPDATE system_user
SET dept_id = CASE user_type
    WHEN 'ADMIN' THEN 130
    WHEN 'CS' THEN 110
    WHEN 'WORKER' THEN 120
    WHEN 'DOCTOR' THEN 200
    ELSE dept_id
END
WHERE dept_id IS NULL;

INSERT INTO system_user_post (user_id, post_id)
SELECT u.user_id, p.post_id
FROM system_user u
JOIN system_post p ON p.post_code = CASE u.user_type
    WHEN 'ADMIN' THEN 'ADMIN_MANAGER'
    WHEN 'CS' THEN 'CUSTOMER_SERVICE'
    WHEN 'WORKER' THEN 'PRODUCTION_WORKER'
    WHEN 'DOCTOR' THEN 'CLINIC_DOCTOR'
END;

INSERT INTO system_menu
    (menu_id, parent_id, menu_code, menu_name, menu_type, route_path, component_path, permission_code, icon, sort_order, status)
VALUES
    (1000, NULL, 'dashboard', '工作台', 'MENU', '/dashboard', 'DashboardView', NULL, 'dashboard', 1, 'ACTIVE'),
    (1100, NULL, 'doctor-orders', '医生订单', 'MENU', '/doctor/orders', 'DoctorOrdersView', 'order:read-doctor', 'order', 10, 'ACTIVE'),
    (1200, NULL, 'internal-orders', '内部订单', 'MENU', '/orders/internal', 'InternalOrdersView', 'order:read-internal', 'order', 20, 'ACTIVE'),
    (1210, 1200, 'production-review', '生产审核', 'MENU', '/workflow/review', 'ProductionReviewView', 'workflow:review-production', 'workflow', 21, 'ACTIVE'),
    (1220, 1200, 'process-instance', '工序实例', 'MENU', '/workflow/process-instance', 'ProcessInstanceView', 'workflow:read-internal', 'workflow', 22, 'ACTIVE'),
    (1230, 1200, 'workflow-assign', '派工转派', 'MENU', '/workflow/assign', 'WorkflowAssignView', 'workflow:assign', 'workflow', 23, 'ACTIVE'),
    (1300, NULL, 'worker-tasks', '我的任务', 'MENU', '/tasks/mine', 'WorkerTasksView', 'workflow:operate-assigned', 'tasks', 30, 'ACTIVE'),
    (1310, 1300, 'check-records', '入检出检', 'MENU', '/checks', 'CheckRecordsView', 'check:read-internal', 'check', 31, 'ACTIVE'),
    (1320, 1300, 'worklog-self', '工时记录', 'MENU', '/worklogs/self', 'WorklogSelfView', 'worklog:write-self', 'clock', 32, 'ACTIVE'),
    (1400, NULL, 'performance', '绩效统计', 'MENU', '/performance', 'PerformanceView', 'performance:read-self', 'chart', 40, 'ACTIVE'),
    (1500, NULL, 'file-center', '文件中心', 'MENU', '/files', 'FileCenterView', 'file:manage-internal', 'file', 50, 'ACTIVE'),
    (1510, NULL, 'doctor-files', '医生文件', 'MENU', '/doctor/files', 'DoctorFilesView', 'file:access-doctor', 'file', 51, 'ACTIVE'),
    (1600, NULL, 'collaboration', '消息设计账单物流', 'MENU', '/collaboration', 'CollaborationView', 'message:manage', 'message', 60, 'ACTIVE'),
    (1700, NULL, 'ai-cs', '客服 AI', 'MENU', '/ai/cs', 'CsAiView', 'ai:cs', 'ai', 70, 'ACTIVE'),
    (1710, NULL, 'ai-production', '生产 AI', 'MENU', '/ai/production', 'ProductionAiView', 'ai:production', 'ai', 71, 'ACTIVE'),
    (1720, NULL, 'ai-doctor', '医生 AI', 'MENU', '/doctor/ai', 'DoctorAiView', 'ai:doctor', 'ai', 72, 'ACTIVE'),
    (1800, NULL, 'system-rbac', '系统权限', 'MENU', '/system/rbac', 'SystemRbacView', 'workflow:assign', 'settings', 80, 'ACTIVE');

INSERT INTO system_role_menu (role_id, menu_id)
SELECT DISTINCT r.role_id, m.menu_id
FROM system_role r
JOIN system_menu m
WHERE r.role_code = 'ADMIN'
  AND m.status = 'ACTIVE';

INSERT INTO system_role_menu (role_id, menu_id)
SELECT DISTINCT r.role_id, m.menu_id
FROM system_role r
JOIN system_role_permission rp ON rp.role_id = r.role_id
JOIN system_permission p ON p.permission_id = rp.permission_id
JOIN system_menu m ON m.permission_code = p.permission_code OR m.permission_code IS NULL
WHERE r.role_code <> 'ADMIN'
  AND m.status = 'ACTIVE';
