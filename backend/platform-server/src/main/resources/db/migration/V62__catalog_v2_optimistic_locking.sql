ALTER TABLE catalog_category_v2
    ADD COLUMN lock_version INT NOT NULL DEFAULT 0 AFTER status,
    ADD CONSTRAINT chk_catalog_category_lock_version CHECK (lock_version >= 0);

ALTER TABLE catalog_product_v2
    ADD COLUMN lock_version INT NOT NULL DEFAULT 0 AFTER status,
    ADD CONSTRAINT chk_catalog_product_lock_version CHECK (lock_version >= 0);

ALTER TABLE catalog_product_variant_v2
    ADD COLUMN lock_version INT NOT NULL DEFAULT 0 AFTER status,
    ADD CONSTRAINT chk_catalog_variant_lock_version CHECK (lock_version >= 0);

ALTER TABLE catalog_material_v2
    ADD COLUMN lock_version INT NOT NULL DEFAULT 0 AFTER status,
    ADD CONSTRAINT chk_catalog_material_lock_version CHECK (lock_version >= 0);

ALTER TABLE catalog_accessory_v2
    ADD COLUMN lock_version INT NOT NULL DEFAULT 0 AFTER status,
    ADD CONSTRAINT chk_catalog_accessory_lock_version CHECK (lock_version >= 0);

ALTER TABLE catalog_product_material_binding_v2
    ADD COLUMN lock_version INT NOT NULL DEFAULT 0 AFTER status,
    ADD CONSTRAINT chk_catalog_product_material_lock_version CHECK (lock_version >= 0);

ALTER TABLE catalog_product_accessory_binding_v2
    ADD COLUMN lock_version INT NOT NULL DEFAULT 0 AFTER status,
    ADD CONSTRAINT chk_catalog_product_accessory_lock_version CHECK (lock_version >= 0);
