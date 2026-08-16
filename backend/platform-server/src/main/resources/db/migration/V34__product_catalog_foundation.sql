CREATE TABLE product_catalog (
    product_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    product_type VARCHAR(64) NOT NULL,
    product_name VARCHAR(128) NOT NULL,
    material_spec VARCHAR(255) NULL,
    base_price_cents BIGINT NOT NULL,
    currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    price_note VARCHAR(255) NULL,
    created_by_user_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_product_catalog_type (product_type),
    KEY idx_product_catalog_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO product_catalog
    (product_type, product_name, material_spec, base_price_cents, currency, status, price_note)
SELECT 'REGULAR_CROWN', '常规牙冠', '一期默认产品；材料规格待客户确认', 1, 'CNY', 'ACTIVE', '种子占位价；不代表客户正式报价'
WHERE NOT EXISTS (
    SELECT 1 FROM product_catalog WHERE product_type = 'REGULAR_CROWN'
);

INSERT INTO system_permission (permission_code, permission_name, module_code, status)
SELECT 'product:manage', '产品参数与价格维护', 'product', 'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM system_permission WHERE permission_code = 'product:manage'
);

INSERT INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p ON p.permission_code = 'product:manage'
LEFT JOIN system_role_permission existing
  ON existing.role_id = r.role_id
 AND existing.permission_id = p.permission_id
WHERE r.role_code IN ('ADMIN', 'CS')
  AND existing.role_id IS NULL;

INSERT INTO system_menu
    (menu_id, parent_id, menu_code, menu_name, menu_type, route_path, component_path, permission_code, icon, sort_order, status)
SELECT 1120, NULL, 'product-catalog', '产品管理', 'MENU', '/products', 'ProductCatalogView',
       'product:manage', 'product', 12, 'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM system_menu WHERE menu_code = 'product-catalog'
);

INSERT INTO system_role_menu (role_id, menu_id)
SELECT DISTINCT r.role_id, m.menu_id
FROM system_role r
JOIN system_menu m ON m.menu_code = 'product-catalog'
LEFT JOIN system_role_menu existing
  ON existing.role_id = r.role_id
 AND existing.menu_id = m.menu_id
WHERE r.role_code IN ('ADMIN', 'CS')
  AND existing.role_id IS NULL;
