CREATE TABLE catalog_config_version (
    config_version_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    version_no INT NOT NULL,
    version_name VARCHAR(128) NOT NULL,
    publication_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    based_on_version_id BIGINT NULL,
    effective_at DATETIME(3) NULL,
    published_at DATETIME(3) NULL,
    published_by_user_id BIGINT NULL,
    lock_version INT NOT NULL DEFAULT 0,
    created_by_user_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_catalog_config_version_no (version_no),
    KEY idx_catalog_config_version_status (publication_status, effective_at),
    CONSTRAINT fk_catalog_config_based_on
        FOREIGN KEY (based_on_version_id) REFERENCES catalog_config_version (config_version_id),
    CONSTRAINT chk_catalog_config_publication_status
        CHECK (publication_status IN ('DRAFT', 'ACTIVE', 'INACTIVE')),
    CONSTRAINT chk_catalog_config_lock_version CHECK (lock_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE catalog_category_v2 (
    category_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_version_id BIGINT NOT NULL,
    category_code VARCHAR(96) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_catalog_category_version_code (config_version_id, category_code),
    KEY idx_catalog_category_version_sort (config_version_id, status, sort_order),
    CONSTRAINT fk_catalog_category_version
        FOREIGN KEY (config_version_id) REFERENCES catalog_config_version (config_version_id),
    CONSTRAINT chk_catalog_category_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE catalog_product_v2 (
    product_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_version_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    product_code VARCHAR(96) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    workflow_product_type VARCHAR(64) NULL,
    tooth_rule_code VARCHAR(96) NULL,
    pricing_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_QUOTE',
    base_price_cents BIGINT NULL,
    currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_catalog_product_version_code (config_version_id, product_code),
    KEY idx_catalog_product_category_sort (category_id, status, sort_order),
    CONSTRAINT fk_catalog_product_version
        FOREIGN KEY (config_version_id) REFERENCES catalog_config_version (config_version_id),
    CONSTRAINT fk_catalog_product_category
        FOREIGN KEY (category_id) REFERENCES catalog_category_v2 (category_id),
    CONSTRAINT chk_catalog_product_pricing_status
        CHECK (pricing_status IN ('PENDING_QUOTE', 'PRICED')),
    CONSTRAINT chk_catalog_product_price
        CHECK (
            (pricing_status = 'PENDING_QUOTE' AND base_price_cents IS NULL)
            OR (pricing_status = 'PRICED' AND base_price_cents IS NOT NULL AND base_price_cents >= 0)
        ),
    CONSTRAINT chk_catalog_product_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE catalog_product_variant_v2 (
    variant_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_version_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    variant_code VARCHAR(96) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    attributes_json JSON NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_catalog_variant_version_code (config_version_id, variant_code),
    KEY idx_catalog_variant_product_sort (product_id, status, sort_order),
    CONSTRAINT fk_catalog_variant_version
        FOREIGN KEY (config_version_id) REFERENCES catalog_config_version (config_version_id),
    CONSTRAINT fk_catalog_variant_product
        FOREIGN KEY (product_id) REFERENCES catalog_product_v2 (product_id),
    CONSTRAINT chk_catalog_variant_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE catalog_material_v2 (
    material_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_version_id BIGINT NOT NULL,
    material_code VARCHAR(96) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    material_family VARCHAR(64) NULL,
    brand_name VARCHAR(128) NULL,
    specification VARCHAR(255) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_catalog_material_version_code (config_version_id, material_code),
    KEY idx_catalog_material_version_sort (config_version_id, status, sort_order),
    CONSTRAINT fk_catalog_material_version
        FOREIGN KEY (config_version_id) REFERENCES catalog_config_version (config_version_id),
    CONSTRAINT chk_catalog_material_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE catalog_material_color_v2 (
    material_color_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    material_id BIGINT NOT NULL,
    semantic_type VARCHAR(32) NOT NULL,
    color_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_catalog_material_color (material_id, semantic_type, color_code),
    KEY idx_catalog_material_color_sort (material_id, semantic_type, status, sort_order),
    CONSTRAINT fk_catalog_material_color_material
        FOREIGN KEY (material_id) REFERENCES catalog_material_v2 (material_id),
    CONSTRAINT chk_catalog_material_color_semantic
        CHECK (semantic_type IN ('TOOTH_SHADE', 'GINGIVAL_SHADE', 'DENTURE_BASE_SHADE', 'ALIGNER_COLOR')),
    CONSTRAINT chk_catalog_material_color_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE catalog_accessory_v2 (
    accessory_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_version_id BIGINT NOT NULL,
    accessory_code VARCHAR(96) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    quantity_supported TINYINT NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_catalog_accessory_version_code (config_version_id, accessory_code),
    KEY idx_catalog_accessory_version_sort (config_version_id, status, sort_order),
    CONSTRAINT fk_catalog_accessory_version
        FOREIGN KEY (config_version_id) REFERENCES catalog_config_version (config_version_id),
    CONSTRAINT chk_catalog_accessory_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE catalog_alias_v2 (
    alias_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_version_id BIGINT NOT NULL,
    canonical_type VARCHAR(32) NOT NULL,
    canonical_id BIGINT NOT NULL,
    alias_text VARCHAR(255) NOT NULL,
    normalized_alias VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_catalog_alias_version_text (config_version_id, canonical_type, normalized_alias),
    KEY idx_catalog_alias_target (canonical_type, canonical_id, status),
    CONSTRAINT fk_catalog_alias_version
        FOREIGN KEY (config_version_id) REFERENCES catalog_config_version (config_version_id),
    CONSTRAINT chk_catalog_alias_type
        CHECK (canonical_type IN ('PRODUCT', 'PRODUCT_VARIANT', 'MATERIAL', 'ACCESSORY')),
    CONSTRAINT chk_catalog_alias_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE catalog_product_material_binding_v2 (
    binding_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_version_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    variant_id BIGINT NULL,
    material_id BIGINT NOT NULL,
    required_flag TINYINT NOT NULL DEFAULT 0,
    selection_mode VARCHAR(16) NOT NULL DEFAULT 'SINGLE',
    default_flag TINYINT NOT NULL DEFAULT 0,
    min_quantity INT NULL,
    max_quantity INT NULL,
    applicable_tooth_rule_json JSON NULL,
    price_increment_cents BIGINT NULL,
    time_adjustment_minutes INT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_catalog_product_material_binding
        (config_version_id, product_id, variant_id, material_id),
    KEY idx_catalog_product_material_product (product_id, status, sort_order),
    CONSTRAINT fk_catalog_product_material_version
        FOREIGN KEY (config_version_id) REFERENCES catalog_config_version (config_version_id),
    CONSTRAINT fk_catalog_product_material_product
        FOREIGN KEY (product_id) REFERENCES catalog_product_v2 (product_id),
    CONSTRAINT fk_catalog_product_material_variant
        FOREIGN KEY (variant_id) REFERENCES catalog_product_variant_v2 (variant_id),
    CONSTRAINT fk_catalog_product_material_material
        FOREIGN KEY (material_id) REFERENCES catalog_material_v2 (material_id),
    CONSTRAINT chk_catalog_product_material_selection
        CHECK (selection_mode IN ('SINGLE', 'MULTIPLE')),
    CONSTRAINT chk_catalog_product_material_quantity
        CHECK (
            (min_quantity IS NULL OR min_quantity >= 0)
            AND (max_quantity IS NULL OR max_quantity >= 0)
            AND (min_quantity IS NULL OR max_quantity IS NULL OR min_quantity <= max_quantity)
        ),
    CONSTRAINT chk_catalog_product_material_price
        CHECK (price_increment_cents IS NULL OR price_increment_cents >= 0),
    CONSTRAINT chk_catalog_product_material_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE catalog_product_accessory_binding_v2 (
    binding_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_version_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    variant_id BIGINT NULL,
    accessory_id BIGINT NOT NULL,
    required_flag TINYINT NOT NULL DEFAULT 0,
    default_flag TINYINT NOT NULL DEFAULT 0,
    min_quantity INT NULL,
    max_quantity INT NULL,
    applicable_tooth_rule_json JSON NULL,
    price_increment_cents BIGINT NULL,
    time_adjustment_minutes INT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_catalog_product_accessory_binding
        (config_version_id, product_id, variant_id, accessory_id),
    KEY idx_catalog_product_accessory_product (product_id, status, sort_order),
    CONSTRAINT fk_catalog_product_accessory_version
        FOREIGN KEY (config_version_id) REFERENCES catalog_config_version (config_version_id),
    CONSTRAINT fk_catalog_product_accessory_product
        FOREIGN KEY (product_id) REFERENCES catalog_product_v2 (product_id),
    CONSTRAINT fk_catalog_product_accessory_variant
        FOREIGN KEY (variant_id) REFERENCES catalog_product_variant_v2 (variant_id),
    CONSTRAINT fk_catalog_product_accessory_accessory
        FOREIGN KEY (accessory_id) REFERENCES catalog_accessory_v2 (accessory_id),
    CONSTRAINT chk_catalog_product_accessory_quantity
        CHECK (
            (min_quantity IS NULL OR min_quantity >= 0)
            AND (max_quantity IS NULL OR max_quantity >= 0)
            AND (min_quantity IS NULL OR max_quantity IS NULL OR min_quantity <= max_quantity)
        ),
    CONSTRAINT chk_catalog_product_accessory_price
        CHECK (price_increment_cents IS NULL OR price_increment_cents >= 0),
    CONSTRAINT chk_catalog_product_accessory_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE catalog_rule_v2 (
    rule_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_version_id BIGINT NOT NULL,
    product_id BIGINT NULL,
    variant_id BIGINT NULL,
    rule_type VARCHAR(32) NOT NULL,
    rule_code VARCHAR(96) NOT NULL,
    rule_schema_json JSON NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_catalog_rule_version_code (config_version_id, rule_type, rule_code),
    KEY idx_catalog_rule_product_type (product_id, variant_id, rule_type, status, sort_order),
    CONSTRAINT fk_catalog_rule_version
        FOREIGN KEY (config_version_id) REFERENCES catalog_config_version (config_version_id),
    CONSTRAINT fk_catalog_rule_product
        FOREIGN KEY (product_id) REFERENCES catalog_product_v2 (product_id),
    CONSTRAINT fk_catalog_rule_variant
        FOREIGN KEY (variant_id) REFERENCES catalog_product_variant_v2 (variant_id),
    CONSTRAINT chk_catalog_rule_type
        CHECK (rule_type IN ('FORM_SCHEMA', 'TOOTH', 'UPLOAD', 'PRICE', 'LEAD_TIME', 'WORKFLOW')),
    CONSTRAINT chk_catalog_rule_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE order_catalog_snapshot (
    snapshot_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    config_version_id BIGINT NULL,
    product_snapshot JSON NOT NULL,
    form_schema_snapshot JSON NULL,
    normalized_form_values JSON NULL,
    price_snapshot JSON NULL,
    workflow_mapping_snapshot JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_order_catalog_snapshot_order (order_id),
    KEY idx_order_catalog_snapshot_version (config_version_id),
    CONSTRAINT fk_order_catalog_snapshot_order
        FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT fk_order_catalog_snapshot_version
        FOREIGN KEY (config_version_id) REFERENCES catalog_config_version (config_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE catalog_change_audit (
    audit_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_version_id BIGINT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id BIGINT NULL,
    action_type VARCHAR(32) NOT NULL,
    before_value JSON NULL,
    after_value JSON NULL,
    operator_user_id BIGINT NULL,
    reason VARCHAR(512) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_catalog_change_audit_version (config_version_id, created_at),
    KEY idx_catalog_change_audit_entity (entity_type, entity_id, created_at),
    CONSTRAINT fk_catalog_change_audit_version
        FOREIGN KEY (config_version_id) REFERENCES catalog_config_version (config_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO catalog_config_version
    (version_no, version_name, publication_status, created_by_user_id)
SELECT 1, '客户资料标准化草稿 2026-07-31', 'DRAFT', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM catalog_config_version WHERE version_no = 1
);

INSERT INTO system_permission (permission_code, permission_name, module_code, status)
SELECT permission_code, permission_name, 'catalog', 'ACTIVE'
FROM (
    SELECT 'catalog:manage' AS permission_code, '产品配置中心维护' AS permission_name
    UNION ALL SELECT 'catalog:publish', '产品配置版本发布'
) permissions
WHERE NOT EXISTS (
    SELECT 1
    FROM system_permission existing
    WHERE existing.permission_code = permissions.permission_code
);

INSERT INTO system_role_permission (role_id, permission_id)
SELECT role.role_id, permission.permission_id
FROM system_role role
JOIN system_permission permission
  ON permission.permission_code IN ('catalog:manage', 'catalog:publish')
LEFT JOIN system_role_permission existing
  ON existing.role_id = role.role_id
 AND existing.permission_id = permission.permission_id
WHERE role.role_code = 'ADMIN'
  AND existing.role_id IS NULL;

INSERT INTO system_menu
    (menu_id, parent_id, menu_code, menu_name, menu_type, route_path,
     component_path, permission_code, icon, sort_order, status)
SELECT
    1130, NULL, 'catalog-configuration-center', '产品配置中心', 'MENU',
    '/admin/catalog', 'CatalogConfigurationCenterView',
    'catalog:manage', 'product', 13, 'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM system_menu WHERE menu_code = 'catalog-configuration-center'
);

INSERT INTO system_role_menu (role_id, menu_id)
SELECT role.role_id, menu.menu_id
FROM system_role role
JOIN system_menu menu ON menu.menu_code = 'catalog-configuration-center'
LEFT JOIN system_role_menu existing
  ON existing.role_id = role.role_id
 AND existing.menu_id = menu.menu_id
WHERE role.role_code = 'ADMIN'
  AND existing.role_id IS NULL;
