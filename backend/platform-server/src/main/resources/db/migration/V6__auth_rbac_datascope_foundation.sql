CREATE TABLE system_user (
    user_id BIGINT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    clinic_id BIGINT NULL,
    user_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_system_user_username (username),
    KEY idx_system_user_clinic (clinic_id, status),
    CONSTRAINT fk_system_user_clinic FOREIGN KEY (clinic_id) REFERENCES clinic (clinic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE system_role (
    role_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    role_code VARCHAR(32) NOT NULL,
    role_name VARCHAR(64) NOT NULL,
    data_scope VARCHAR(32) NOT NULL DEFAULT 'ALL',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_system_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE system_permission (
    permission_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    permission_code VARCHAR(96) NOT NULL,
    permission_name VARCHAR(128) NOT NULL,
    module_code VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_system_permission_code (permission_code),
    KEY idx_system_permission_module (module_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE system_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_system_user_role_user FOREIGN KEY (user_id) REFERENCES system_user (user_id),
    CONSTRAINT fk_system_user_role_role FOREIGN KEY (role_id) REFERENCES system_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE system_role_permission (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_system_role_permission_role FOREIGN KEY (role_id) REFERENCES system_role (role_id),
    CONSTRAINT fk_system_role_permission_permission FOREIGN KEY (permission_id) REFERENCES system_permission (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO system_role (role_code, role_name, data_scope, status)
VALUES
    ('ADMIN', '系统管理员', 'ALL', 'ACTIVE'),
    ('CS', '客服', 'ALL', 'ACTIVE'),
    ('WORKER', '生产员工', 'SELF', 'ACTIVE'),
    ('DOCTOR', '医生', 'CLINIC', 'ACTIVE');

INSERT INTO system_permission (permission_code, permission_name, module_code, status)
VALUES
    ('order:read-internal', '读取内部订单', 'order', 'ACTIVE'),
    ('order:read-doctor', '读取医生订单', 'order', 'ACTIVE'),
    ('workflow:review-production', '生产审核', 'workflow', 'ACTIVE'),
    ('workflow:read-internal', '读取工序实例', 'workflow', 'ACTIVE'),
    ('workflow:assign', '派工转派', 'workflow', 'ACTIVE'),
    ('workflow:operate-assigned', '操作本人节点', 'workflow', 'ACTIVE'),
    ('check:read-internal', '读取检查记录', 'check', 'ACTIVE'),
    ('check:write', '提交检查记录', 'check', 'ACTIVE'),
    ('worklog:write-self', '记录本人工时', 'worklog', 'ACTIVE'),
    ('performance:read-all', '读取全部绩效', 'performance', 'ACTIVE'),
    ('performance:read-self', '读取本人绩效', 'performance', 'ACTIVE'),
    ('file:manage-internal', '管理内部文件', 'file', 'ACTIVE'),
    ('file:access-doctor', '访问医生可见文件', 'file', 'ACTIVE'),
    ('message:manage', '管理消息设计稿账单物流', 'collaboration', 'ACTIVE'),
    ('ai:cs', '客服 AI', 'ai', 'ACTIVE'),
    ('ai:doctor', '医生 AI', 'ai', 'ACTIVE'),
    ('ai:production', '生产 AI', 'ai', 'ACTIVE');

INSERT INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p
WHERE r.role_code = 'ADMIN';

INSERT INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p ON p.permission_code IN (
    'order:read-internal',
    'workflow:review-production',
    'workflow:read-internal',
    'workflow:assign',
    'check:read-internal',
    'file:manage-internal',
    'message:manage',
    'ai:cs',
    'ai:production'
)
WHERE r.role_code = 'CS';

INSERT INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p ON p.permission_code IN (
    'workflow:read-internal',
    'workflow:operate-assigned',
    'check:read-internal',
    'check:write',
    'worklog:write-self',
    'performance:read-self',
    'file:manage-internal',
    'ai:production'
)
WHERE r.role_code = 'WORKER';

INSERT INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p ON p.permission_code IN (
    'order:read-doctor',
    'file:access-doctor',
    'ai:doctor'
)
WHERE r.role_code = 'DOCTOR';
