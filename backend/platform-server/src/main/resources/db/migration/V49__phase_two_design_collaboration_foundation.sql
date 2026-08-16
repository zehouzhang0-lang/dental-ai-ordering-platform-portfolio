-- Phase 2 design collaboration foundation.
-- Design leaders remain WORKER accounts and receive review capability through
-- a direct user-permission assignment instead of a new role.

CREATE TABLE system_user_permission (
    user_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, permission_id),
    KEY idx_system_user_permission_permission (permission_id, user_id),
    CONSTRAINT fk_system_user_permission_user
        FOREIGN KEY (user_id) REFERENCES system_user (user_id),
    CONSTRAINT fk_system_user_permission_permission
        FOREIGN KEY (permission_id) REFERENCES system_permission (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO system_permission (permission_code, permission_name, module_code, status)
VALUES
    ('design-task:claim', '认领设计任务', 'design', 'ACTIVE'),
    ('design-task:operate-self', '操作本人设计任务', 'design', 'ACTIVE'),
    ('design-draft:internal-review', '设计稿内部审核', 'design', 'ACTIVE'),
    ('design-task:manage', '管理设计任务', 'design', 'ACTIVE'),
    ('design-task:read-progress', '读取设计任务进度', 'design', 'ACTIVE')
ON DUPLICATE KEY UPDATE
    permission_name = VALUES(permission_name),
    module_code = VALUES(module_code),
    status = VALUES(status);

INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p ON p.permission_code IN (
    'design-task:claim',
    'design-task:operate-self'
)
WHERE r.role_code = 'WORKER';

INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p ON p.permission_code IN (
    'design-draft:internal-review',
    'design-task:manage',
    'design-task:read-progress'
)
WHERE r.role_code = 'ADMIN';

INSERT IGNORE INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p ON p.permission_code = 'design-task:read-progress'
WHERE r.role_code = 'CS';

CREATE TABLE design_task (
    design_task_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    task_status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    assigned_user_id BIGINT NULL,
    claimed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_design_task_order (order_id),
    KEY idx_design_task_pool (task_status, created_at),
    KEY idx_design_task_assignee (assigned_user_id, task_status, updated_at),
    CONSTRAINT fk_design_task_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE design_draft
    ADD COLUMN design_task_id BIGINT NULL AFTER design_draft_id,
    ADD COLUMN submission_key VARCHAR(128) NULL AFTER version_no,
    ADD COLUMN upload_note VARCHAR(500) NULL AFTER submission_key,
    ADD COLUMN submitted_at DATETIME(3) NULL AFTER doctor_confirmed_at,
    ADD COLUMN doctor_visible_at DATETIME(3) NULL AFTER submitted_at,
    ADD COLUMN internal_reviewer_user_id BIGINT NULL AFTER doctor_visible_at,
    ADD COLUMN internal_reviewed_at DATETIME(3) NULL AFTER internal_reviewer_user_id,
    ADD UNIQUE KEY uk_design_draft_submission (design_task_id, submission_key),
    ADD KEY idx_design_draft_task_version (design_task_id, version_no),
    ADD KEY idx_design_draft_doctor_visible (order_id, doctor_visible_at),
    ADD CONSTRAINT fk_design_draft_task
        FOREIGN KEY (design_task_id) REFERENCES design_task (design_task_id);

-- Preserve legacy rows while adopting the phase-2 vocabulary.
UPDATE design_draft
SET draft_status = CASE draft_status
        WHEN 'PENDING_CS_REVIEW' THEN 'PENDING_REVIEW'
        WHEN 'CS_REJECTED' THEN 'INTERNAL_REJECTED'
        WHEN 'PENDING_DOCTOR_CONFIRM' THEN 'PENDING_DOCTOR'
        ELSE draft_status
    END,
    submitted_at = COALESCE(submitted_at, created_at),
    doctor_visible_at = CASE
        WHEN draft_status IN (
            'PENDING_DOCTOR_CONFIRM',
            'PENDING_DOCTOR',
            'DOCTOR_CONFIRMED',
            'DOCTOR_REJECTED'
        ) THEN COALESCE(doctor_visible_at, updated_at, created_at)
        ELSE doctor_visible_at
    END;

INSERT INTO design_task
    (order_id, task_status, assigned_user_id, claimed_at, created_at, updated_at)
SELECT
    latest.order_id,
    CASE latest.draft_status
        WHEN 'PENDING_REVIEW' THEN 'INTERNAL_REVIEW'
        WHEN 'INTERNAL_REJECTED' THEN 'INTERNAL_REJECTED'
        WHEN 'PENDING_DOCTOR' THEN 'PENDING_DOCTOR'
        WHEN 'DOCTOR_CONFIRMED' THEN 'DOCTOR_CONFIRMED'
        WHEN 'DOCTOR_REJECTED' THEN 'DOCTOR_REJECTED'
        ELSE 'CLAIMED'
    END,
    latest.uploaded_by_user_id,
    CASE
        WHEN latest.uploaded_by_user_id IS NULL THEN NULL
        ELSE latest.created_at
    END,
    latest.created_at,
    latest.updated_at
FROM design_draft latest
JOIN (
    SELECT order_id, MAX(version_no) AS version_no
    FROM design_draft
    GROUP BY order_id
) latest_version
  ON latest_version.order_id = latest.order_id
 AND latest_version.version_no = latest.version_no;

UPDATE design_draft d
JOIN design_task t ON t.order_id = d.order_id
SET d.design_task_id = t.design_task_id
WHERE d.design_task_id IS NULL;

CREATE TABLE design_task_event (
    event_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    design_task_id BIGINT NOT NULL,
    design_draft_id BIGINT NULL,
    event_type VARCHAR(64) NOT NULL,
    actor_user_id BIGINT NULL,
    actor_role VARCHAR(32) NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NOT NULL,
    from_assignee_user_id BIGINT NULL,
    to_assignee_user_id BIGINT NULL,
    reason VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_design_task_event_task (design_task_id, created_at, event_id),
    KEY idx_design_task_event_draft (design_draft_id, created_at),
    CONSTRAINT fk_design_task_event_task
        FOREIGN KEY (design_task_id) REFERENCES design_task (design_task_id),
    CONSTRAINT fk_design_task_event_draft
        FOREIGN KEY (design_draft_id) REFERENCES design_draft (design_draft_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
