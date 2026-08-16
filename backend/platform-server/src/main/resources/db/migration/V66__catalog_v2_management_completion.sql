ALTER TABLE catalog_material_color_v2
    ADD COLUMN lock_version INT NOT NULL DEFAULT 0 AFTER status,
    ADD CONSTRAINT chk_catalog_material_color_lock_version CHECK (lock_version >= 0);

ALTER TABLE catalog_alias_v2
    ADD COLUMN lock_version INT NOT NULL DEFAULT 0 AFTER status,
    ADD CONSTRAINT chk_catalog_alias_lock_version CHECK (lock_version >= 0);

ALTER TABLE catalog_rule_v2
    ADD COLUMN lock_version INT NOT NULL DEFAULT 0 AFTER status,
    ADD CONSTRAINT chk_catalog_rule_lock_version CHECK (lock_version >= 0);

ALTER TABLE catalog_product_material_binding_v2
    ADD COLUMN selection_group_code VARCHAR(96) NOT NULL DEFAULT 'MAIN_MATERIAL'
        AFTER material_id,
    ADD KEY idx_catalog_product_material_group
        (config_version_id, product_id, variant_id, selection_group_code, status);

ALTER TABLE catalog_product_accessory_binding_v2
    ADD COLUMN selection_group_code VARCHAR(96) NOT NULL DEFAULT 'ACCESSORIES'
        AFTER accessory_id,
    ADD KEY idx_catalog_product_accessory_group
        (config_version_id, product_id, variant_id, selection_group_code, status);
