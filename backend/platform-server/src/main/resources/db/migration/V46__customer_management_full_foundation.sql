ALTER TABLE clinic
    ADD COLUMN clinic_code VARCHAR(32) NULL AFTER clinic_id,
    ADD COLUMN business_region VARCHAR(64) NULL AFTER contact_phone,
    ADD COLUMN salesperson VARCHAR(64) NULL AFTER business_region,
    ADD COLUMN customer_type VARCHAR(32) NULL AFTER salesperson,
    ADD COLUMN settlement_type VARCHAR(32) NULL AFTER customer_type,
    ADD COLUMN organization_nature VARCHAR(32) NULL AFTER settlement_type,
    ADD COLUMN business_level VARCHAR(32) NULL AFTER organization_nature,
    ADD COLUMN default_shipping_method VARCHAR(64) NULL AFTER business_level,
    ADD COLUMN contact_email VARCHAR(128) NULL AFTER default_shipping_method;

UPDATE clinic
SET clinic_code = CONCAT('KH', LPAD(clinic_id, 6, '0'))
WHERE clinic_code IS NULL OR clinic_code = '';

ALTER TABLE clinic
    ADD UNIQUE KEY uk_clinic_code (clinic_code),
    ADD KEY idx_clinic_business_filter (business_region, customer_type, settlement_type, business_level, status);

CREATE TABLE clinic_invoice_profile (
    invoice_profile_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    clinic_id BIGINT NOT NULL,
    invoice_title VARCHAR(160) NULL,
    tax_number VARCHAR(64) NULL,
    bank_name VARCHAR(128) NULL,
    bank_account VARCHAR(64) NULL,
    registered_address VARCHAR(255) NULL,
    registered_phone VARCHAR(32) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_clinic_invoice_profile (clinic_id),
    CONSTRAINT fk_clinic_invoice_profile_clinic FOREIGN KEY (clinic_id) REFERENCES clinic (clinic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE clinic_shipping_address (
    address_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    clinic_id BIGINT NOT NULL,
    address_label VARCHAR(64) NULL,
    recipient_name VARCHAR(64) NOT NULL,
    recipient_phone VARCHAR(32) NOT NULL,
    province VARCHAR(64) NULL,
    city VARCHAR(64) NULL,
    district VARCHAR(64) NULL,
    detail_address VARCHAR(255) NOT NULL,
    shipping_method VARCHAR(64) NULL,
    default_flag TINYINT(1) NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_clinic_shipping_address (clinic_id, status, default_flag),
    CONSTRAINT fk_clinic_shipping_address_clinic FOREIGN KEY (clinic_id) REFERENCES clinic (clinic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE clinic_doctor_contact (
    doctor_contact_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    clinic_id BIGINT NOT NULL,
    doctor_name VARCHAR(64) NOT NULL,
    phone VARCHAR(32) NULL,
    email VARCHAR(128) NULL,
    position_title VARCHAR(64) NULL,
    primary_flag TINYINT(1) NOT NULL DEFAULT 0,
    notes VARCHAR(255) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_clinic_doctor_contact (clinic_id, status, primary_flag),
    CONSTRAINT fk_clinic_doctor_contact_clinic FOREIGN KEY (clinic_id) REFERENCES clinic (clinic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE clinic_business_document (
    document_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    clinic_id BIGINT NOT NULL,
    document_category VARCHAR(32) NOT NULL,
    document_name VARCHAR(128) NOT NULL,
    document_no VARCHAR(64) NULL,
    valid_from DATE NULL,
    valid_until DATE NULL,
    file_id BIGINT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    notes VARCHAR(255) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_clinic_business_document (clinic_id, document_category, status, valid_until),
    CONSTRAINT fk_clinic_business_document_clinic FOREIGN KEY (clinic_id) REFERENCES clinic (clinic_id),
    CONSTRAINT fk_clinic_business_document_file FOREIGN KEY (file_id) REFERENCES file_resource (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE clinic_product_price (
    clinic_product_price_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    clinic_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    price_cents BIGINT NOT NULL,
    currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
    effective_from DATE NULL,
    effective_until DATE NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    price_note VARCHAR(255) NULL,
    created_by_user_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_clinic_product_price (clinic_id, product_id),
    KEY idx_clinic_product_price_effective (clinic_id, status, effective_from, effective_until),
    CONSTRAINT fk_clinic_product_price_clinic FOREIGN KEY (clinic_id) REFERENCES clinic (clinic_id),
    CONSTRAINT fk_clinic_product_price_product FOREIGN KEY (product_id) REFERENCES product_catalog (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE customer_print_template (
    template_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    template_code VARCHAR(64) NOT NULL,
    template_name VARCHAR(128) NOT NULL,
    document_type VARCHAR(32) NOT NULL,
    layout_style VARCHAR(32) NOT NULL,
    description VARCHAR(255) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_customer_print_template_code (template_code),
    KEY idx_customer_print_template_type (document_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE clinic_print_template_binding (
    binding_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    clinic_id BIGINT NOT NULL,
    document_type VARCHAR(32) NOT NULL,
    template_id BIGINT NOT NULL,
    updated_by_user_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_clinic_print_template_binding (clinic_id, document_type),
    CONSTRAINT fk_clinic_print_template_binding_clinic FOREIGN KEY (clinic_id) REFERENCES clinic (clinic_id),
    CONSTRAINT fk_clinic_print_template_binding_template FOREIGN KEY (template_id) REFERENCES customer_print_template (template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE clinic_blacklist_record (
    blacklist_record_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    clinic_id BIGINT NOT NULL,
    blacklist_status VARCHAR(32) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    overdue_amount_cents BIGINT NOT NULL DEFAULT 0,
    effective_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by_user_id BIGINT NULL,
    released_at DATETIME(3) NULL,
    released_by_user_id BIGINT NULL,
    release_reason VARCHAR(255) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_clinic_blacklist_active (clinic_id, blacklist_status, effective_at),
    CONSTRAINT fk_clinic_blacklist_record_clinic FOREIGN KEY (clinic_id) REFERENCES clinic (clinic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE clinic_change_log (
    change_log_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    clinic_id BIGINT NOT NULL,
    change_type VARCHAR(64) NOT NULL,
    change_summary VARCHAR(255) NOT NULL,
    operator_user_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_clinic_change_log (clinic_id, created_at),
    CONSTRAINT fk_clinic_change_log_clinic FOREIGN KEY (clinic_id) REFERENCES clinic (clinic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE orders
    ADD COLUMN quoted_price_cents BIGINT NULL AFTER product_type,
    ADD COLUMN quoted_price_currency VARCHAR(16) NULL AFTER quoted_price_cents,
    ADD COLUMN pricing_source VARCHAR(32) NULL AFTER quoted_price_currency;

INSERT INTO customer_print_template
    (template_code, template_name, document_type, layout_style, description, status, version)
VALUES
    ('ORDER_STANDARD_V1', '标准下单单', 'ORDER_SHEET', 'STANDARD', '紫色品牌标准版，展示客户、医生、产品和交付要求', 'ACTIVE', 1),
    ('ORDER_COMPACT_V1', '紧凑下单单', 'ORDER_SHEET', 'COMPACT', '高密度紧凑版，适合诊所内部留存', 'ACTIVE', 1),
    ('WORK_STANDARD_V1', '标准生产工单', 'PRODUCTION_WORK_ORDER', 'STANDARD', '标准生产信息编排，仅内部使用', 'ACTIVE', 1),
    ('WORK_COMPACT_V1', '紧凑生产工单', 'PRODUCTION_WORK_ORDER', 'COMPACT', '工位紧凑打印版', 'ACTIVE', 1),
    ('DELIVERY_STANDARD_V1', '标准送货单', 'DELIVERY_NOTE', 'STANDARD', '展示收件人、地址、承运方式和交付清单', 'ACTIVE', 1),
    ('DELIVERY_COMPACT_V1', '紧凑送货单', 'DELIVERY_NOTE', 'COMPACT', '适合随箱打印的紧凑版', 'ACTIVE', 1),
    ('STATEMENT_STANDARD_V1', '标准对账单', 'STATEMENT', 'STANDARD', '展示客户开票资料和账务汇总', 'ACTIVE', 1),
    ('STATEMENT_COMPACT_V1', '紧凑对账单', 'STATEMENT', 'COMPACT', '紧凑对账与盖章版式', 'ACTIVE', 1);

INSERT INTO system_permission (permission_code, permission_name, module_code, status)
SELECT permission_code, permission_name, 'clinic', 'ACTIVE'
FROM (
    SELECT 'clinic:manage' permission_code, '客户完整档案管理' permission_name
    UNION ALL SELECT 'clinic:price:manage', '客户专属价格管理'
    UNION ALL SELECT 'clinic:template:manage', '客户单据模板管理'
    UNION ALL SELECT 'clinic:blacklist:manage', '客户黑名单管理'
) permissions
WHERE NOT EXISTS (
    SELECT 1 FROM system_permission existing WHERE existing.permission_code = permissions.permission_code
);

INSERT INTO system_role_permission (role_id, permission_id)
SELECT role.role_id, permission.permission_id
FROM system_role role
JOIN system_permission permission
  ON permission.permission_code IN ('clinic:manage', 'clinic:price:manage', 'clinic:template:manage', 'clinic:blacklist:manage')
LEFT JOIN system_role_permission existing
  ON existing.role_id = role.role_id AND existing.permission_id = permission.permission_id
WHERE role.role_code IN ('ADMIN', 'CS')
  AND existing.role_id IS NULL;
