-- 隐形正畸专项事实：处方、方案版本、双重门禁、批次与阶段调整均不可覆盖历史。
CREATE TABLE orthodontic_case (
    orthodontic_case_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    aligner_type_code VARCHAR(96) NOT NULL,
    combined_order_id BIGINT NULL,
    case_status VARCHAR(48) NOT NULL DEFAULT 'DRAFT',
    prescription_version INT NOT NULL DEFAULT 1,
    prescription_json JSON NOT NULL,
    total_steps INT NULL,
    lock_version INT NOT NULL DEFAULT 0,
    created_by_user_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_orthodontic_case_order UNIQUE (order_id),
    CONSTRAINT fk_orthodontic_case_order FOREIGN KEY (order_id) REFERENCES orders(order_id),
    CONSTRAINT fk_orthodontic_case_combined_order FOREIGN KEY (combined_order_id) REFERENCES orders(order_id),
    CONSTRAINT chk_orthodontic_case_status CHECK (
        case_status IN (
            'DRAFT', 'PRESCRIPTION_SUBMITTED', 'PLAN_DESIGN',
            'INTERNAL_REVIEW', 'DOCTOR_REVIEW', 'PLAN_CONFIRMED',
            'PRODUCTION', 'COMPLETED', 'CANCELLED'
        )
    ),
    CONSTRAINT chk_orthodontic_total_steps CHECK (total_steps IS NULL OR total_steps BETWEEN 1 AND 999)
);

CREATE TABLE orthodontic_plan_version (
    plan_version_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    orthodontic_case_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    plan_status VARCHAR(48) NOT NULL DEFAULT 'DRAFT',
    plan_file_id BIGINT NULL,
    plan_snapshot_json JSON NOT NULL,
    design_note VARCHAR(2000) NULL,
    created_by_user_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_orthodontic_plan_version UNIQUE (orthodontic_case_id, version_no),
    CONSTRAINT fk_orthodontic_plan_case FOREIGN KEY (orthodontic_case_id)
        REFERENCES orthodontic_case(orthodontic_case_id),
    CONSTRAINT fk_orthodontic_plan_file FOREIGN KEY (plan_file_id) REFERENCES file_resource(file_id),
    CONSTRAINT chk_orthodontic_plan_status CHECK (
        plan_status IN (
            'DRAFT', 'PENDING_INTERNAL_REVIEW', 'INTERNAL_REJECTED',
            'PENDING_DOCTOR_REVIEW', 'DOCTOR_REJECTED', 'DOCTOR_APPROVED', 'SUPERSEDED'
        )
    )
);

CREATE TABLE orthodontic_plan_review (
    plan_review_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    plan_version_id BIGINT NOT NULL,
    review_gate VARCHAR(24) NOT NULL,
    decision VARCHAR(24) NOT NULL,
    reason VARCHAR(1000) NULL,
    reviewer_user_id BIGINT NULL,
    reviewed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_orthodontic_review_plan FOREIGN KEY (plan_version_id)
        REFERENCES orthodontic_plan_version(plan_version_id),
    CONSTRAINT chk_orthodontic_review_gate CHECK (review_gate IN ('INTERNAL', 'DOCTOR')),
    CONSTRAINT chk_orthodontic_review_decision CHECK (decision IN ('APPROVE', 'REJECT'))
);

CREATE TABLE orthodontic_production_batch (
    production_batch_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    orthodontic_case_id BIGINT NOT NULL,
    plan_version_id BIGINT NOT NULL,
    batch_no INT NOT NULL,
    step_from INT NOT NULL,
    step_to INT NOT NULL,
    quantity INT NOT NULL,
    batch_status VARCHAR(32) NOT NULL DEFAULT 'PLANNED',
    lock_version INT NOT NULL DEFAULT 0,
    created_by_user_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_orthodontic_batch_no UNIQUE (orthodontic_case_id, batch_no),
    CONSTRAINT fk_orthodontic_batch_case FOREIGN KEY (orthodontic_case_id)
        REFERENCES orthodontic_case(orthodontic_case_id),
    CONSTRAINT fk_orthodontic_batch_plan FOREIGN KEY (plan_version_id)
        REFERENCES orthodontic_plan_version(plan_version_id),
    CONSTRAINT chk_orthodontic_batch_steps CHECK (
        step_from >= 1 AND step_to >= step_from AND quantity = step_to - step_from + 1
    ),
    CONSTRAINT chk_orthodontic_batch_status CHECK (
        batch_status IN ('PLANNED', 'IN_PRODUCTION', 'COMPLETED', 'CANCELLED')
    )
);

CREATE TABLE orthodontic_change_request (
    change_request_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    orthodontic_case_id BIGINT NOT NULL,
    source_plan_version_id BIGINT NOT NULL,
    source_batch_id BIGINT NULL,
    request_type VARCHAR(32) NOT NULL,
    request_status VARCHAR(32) NOT NULL DEFAULT 'SUBMITTED',
    reason VARCHAR(2000) NOT NULL,
    requested_by_user_id BIGINT NULL,
    reviewed_by_user_id BIGINT NULL,
    reviewed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_orthodontic_change_case FOREIGN KEY (orthodontic_case_id)
        REFERENCES orthodontic_case(orthodontic_case_id),
    CONSTRAINT fk_orthodontic_change_plan FOREIGN KEY (source_plan_version_id)
        REFERENCES orthodontic_plan_version(plan_version_id),
    CONSTRAINT fk_orthodontic_change_batch FOREIGN KEY (source_batch_id)
        REFERENCES orthodontic_production_batch(production_batch_id),
    CONSTRAINT chk_orthodontic_change_type CHECK (
        request_type IN ('STAGE_ADJUSTMENT', 'FOLLOW_UP_PROCESSING')
    ),
    CONSTRAINT chk_orthodontic_change_status CHECK (
        request_status IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED')
    )
);

CREATE TABLE orthodontic_audit (
    audit_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    orthodontic_case_id BIGINT NOT NULL,
    entity_type VARCHAR(48) NOT NULL,
    entity_id BIGINT NULL,
    action_type VARCHAR(48) NOT NULL,
    before_json JSON NULL,
    after_json JSON NULL,
    actor_user_id BIGINT NULL,
    reason VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_orthodontic_audit_case (orthodontic_case_id, created_at),
    CONSTRAINT fk_orthodontic_audit_case FOREIGN KEY (orthodontic_case_id)
        REFERENCES orthodontic_case(orthodontic_case_id)
);

INSERT INTO system_permission(permission_code, permission_name, module_code, status)
SELECT 'workflow:orthodontic-batch:manage', '正畸生产批次维护', 'workflow', 'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM system_permission WHERE permission_code = 'workflow:orthodontic-batch:manage'
);

INSERT INTO system_permission(permission_code, permission_name, module_code, status)
SELECT 'workflow:orthodontic-case:read', '正畸病例内部只读', 'workflow', 'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM system_permission WHERE permission_code = 'workflow:orthodontic-case:read'
);

INSERT IGNORE INTO system_role_permission(role_id, permission_id)
SELECT role.role_id, permission.permission_id
FROM system_role role
JOIN system_permission permission
  ON permission.permission_code IN (
      'workflow:orthodontic-batch:manage',
      'workflow:orthodontic-case:read'
  )
WHERE role.role_code = 'ADMIN';
