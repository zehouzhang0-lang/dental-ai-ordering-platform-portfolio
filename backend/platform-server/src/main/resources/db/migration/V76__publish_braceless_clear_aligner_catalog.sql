-- D-182 source correction for the doctor ordering catalog:
--   动态下单表最终版.docx
--   家红-正畸平台-医生端操作文档.docx
--
-- The earlier "CLEAR_ALIGNER_TYPE_A / 隐形正畸 A 型" row was an inactive
-- internal placeholder without source provenance. Keep it inactive for audit,
-- clone the current immutable catalog, add the confirmed orderable product
-- "无托槽隐形矫治器", and publish the cloned version.

SET @clear_aligner_source_version_id = (
    SELECT config_version_id
    FROM catalog_config_version
    WHERE publication_status = 'ACTIVE'
      AND effective_at <= CURRENT_TIMESTAMP(3)
    ORDER BY effective_at DESC, version_no DESC
    LIMIT 1
);

SET @clear_aligner_next_version_no = (
    SELECT COALESCE(MAX(version_no), 0) + 1
    FROM catalog_config_version
);

INSERT INTO catalog_config_version (
    version_no, version_name, publication_status, based_on_version_id, effective_at
)
VALUES (
    @clear_aligner_next_version_no,
    '隐形正畸正式产品目录 2026-08-01',
    'DRAFT',
    @clear_aligner_source_version_id,
    CURRENT_TIMESTAMP(3)
);

SET @clear_aligner_target_version_id = LAST_INSERT_ID();

INSERT INTO catalog_category_v2
    (config_version_id, category_code, display_name, sort_order, status)
SELECT @clear_aligner_target_version_id, category_code, display_name, sort_order, status
FROM catalog_category_v2
WHERE config_version_id = @clear_aligner_source_version_id;

INSERT INTO catalog_product_v2
    (config_version_id, category_id, product_code, display_name,
     workflow_product_type, tooth_rule_code, pricing_status,
     base_price_cents, currency, sort_order, status)
SELECT @clear_aligner_target_version_id, new_category.category_id,
       old_product.product_code, old_product.display_name,
       old_product.workflow_product_type, old_product.tooth_rule_code,
       old_product.pricing_status, old_product.base_price_cents,
       old_product.currency, old_product.sort_order, old_product.status
FROM catalog_product_v2 old_product
JOIN catalog_category_v2 old_category
  ON old_category.category_id = old_product.category_id
JOIN catalog_category_v2 new_category
  ON new_category.config_version_id = @clear_aligner_target_version_id
 AND new_category.category_code = old_category.category_code
WHERE old_product.config_version_id = @clear_aligner_source_version_id;

INSERT INTO catalog_product_variant_v2
    (config_version_id, product_id, variant_code, display_name,
     attributes_json, sort_order, status)
SELECT @clear_aligner_target_version_id, new_product.product_id,
       old_variant.variant_code, old_variant.display_name,
       old_variant.attributes_json, old_variant.sort_order, old_variant.status
FROM catalog_product_variant_v2 old_variant
JOIN catalog_product_v2 old_product ON old_product.product_id = old_variant.product_id
JOIN catalog_product_v2 new_product
  ON new_product.config_version_id = @clear_aligner_target_version_id
 AND new_product.product_code = old_product.product_code
WHERE old_variant.config_version_id = @clear_aligner_source_version_id;

INSERT INTO catalog_material_v2
    (config_version_id, material_code, display_name, material_family,
     brand_name, specification, sort_order, status)
SELECT @clear_aligner_target_version_id, material_code, display_name,
       material_family, brand_name, specification, sort_order, status
FROM catalog_material_v2
WHERE config_version_id = @clear_aligner_source_version_id;

INSERT INTO catalog_material_color_v2
    (material_id, semantic_type, color_code, display_name, sort_order, status)
SELECT new_material.material_id, old_color.semantic_type, old_color.color_code,
       old_color.display_name, old_color.sort_order, old_color.status
FROM catalog_material_color_v2 old_color
JOIN catalog_material_v2 old_material ON old_material.material_id = old_color.material_id
JOIN catalog_material_v2 new_material
  ON new_material.config_version_id = @clear_aligner_target_version_id
 AND new_material.material_code = old_material.material_code
WHERE old_material.config_version_id = @clear_aligner_source_version_id;

INSERT INTO catalog_accessory_v2
    (config_version_id, accessory_code, display_name,
     quantity_supported, sort_order, status)
SELECT @clear_aligner_target_version_id, accessory_code, display_name,
       quantity_supported, sort_order, status
FROM catalog_accessory_v2
WHERE config_version_id = @clear_aligner_source_version_id;

INSERT INTO catalog_product_material_binding_v2
    (config_version_id, product_id, variant_id, material_id,
     selection_group_code, required_flag, selection_mode, default_flag,
     min_quantity, max_quantity, applicable_tooth_rule_json,
     price_increment_cents, time_adjustment_minutes, sort_order, status)
SELECT @clear_aligner_target_version_id, new_product.product_id,
       new_variant.variant_id, new_material.material_id,
       old_binding.selection_group_code, old_binding.required_flag,
       old_binding.selection_mode, old_binding.default_flag,
       old_binding.min_quantity, old_binding.max_quantity,
       old_binding.applicable_tooth_rule_json,
       old_binding.price_increment_cents, old_binding.time_adjustment_minutes,
       old_binding.sort_order, old_binding.status
FROM catalog_product_material_binding_v2 old_binding
JOIN catalog_product_v2 old_product ON old_product.product_id = old_binding.product_id
JOIN catalog_product_v2 new_product
  ON new_product.config_version_id = @clear_aligner_target_version_id
 AND new_product.product_code = old_product.product_code
JOIN catalog_material_v2 old_material ON old_material.material_id = old_binding.material_id
JOIN catalog_material_v2 new_material
  ON new_material.config_version_id = @clear_aligner_target_version_id
 AND new_material.material_code = old_material.material_code
LEFT JOIN catalog_product_variant_v2 old_variant ON old_variant.variant_id = old_binding.variant_id
LEFT JOIN catalog_product_variant_v2 new_variant
  ON new_variant.config_version_id = @clear_aligner_target_version_id
 AND new_variant.variant_code = old_variant.variant_code
WHERE old_binding.config_version_id = @clear_aligner_source_version_id;

INSERT INTO catalog_product_accessory_binding_v2
    (config_version_id, product_id, variant_id, accessory_id,
     selection_group_code, required_flag, default_flag,
     min_quantity, max_quantity, applicable_tooth_rule_json,
     price_increment_cents, time_adjustment_minutes, sort_order, status)
SELECT @clear_aligner_target_version_id, new_product.product_id,
       new_variant.variant_id, new_accessory.accessory_id,
       old_binding.selection_group_code, old_binding.required_flag,
       old_binding.default_flag, old_binding.min_quantity,
       old_binding.max_quantity, old_binding.applicable_tooth_rule_json,
       old_binding.price_increment_cents, old_binding.time_adjustment_minutes,
       old_binding.sort_order, old_binding.status
FROM catalog_product_accessory_binding_v2 old_binding
JOIN catalog_product_v2 old_product ON old_product.product_id = old_binding.product_id
JOIN catalog_product_v2 new_product
  ON new_product.config_version_id = @clear_aligner_target_version_id
 AND new_product.product_code = old_product.product_code
JOIN catalog_accessory_v2 old_accessory ON old_accessory.accessory_id = old_binding.accessory_id
JOIN catalog_accessory_v2 new_accessory
  ON new_accessory.config_version_id = @clear_aligner_target_version_id
 AND new_accessory.accessory_code = old_accessory.accessory_code
LEFT JOIN catalog_product_variant_v2 old_variant ON old_variant.variant_id = old_binding.variant_id
LEFT JOIN catalog_product_variant_v2 new_variant
  ON new_variant.config_version_id = @clear_aligner_target_version_id
 AND new_variant.variant_code = old_variant.variant_code
WHERE old_binding.config_version_id = @clear_aligner_source_version_id;

INSERT INTO catalog_rule_v2
    (config_version_id, product_id, variant_id, rule_type,
     rule_code, rule_schema_json, sort_order, status)
SELECT @clear_aligner_target_version_id, new_product.product_id,
       new_variant.variant_id, old_rule.rule_type,
       old_rule.rule_code, old_rule.rule_schema_json,
       old_rule.sort_order, old_rule.status
FROM catalog_rule_v2 old_rule
LEFT JOIN catalog_product_v2 old_product ON old_product.product_id = old_rule.product_id
LEFT JOIN catalog_product_v2 new_product
  ON new_product.config_version_id = @clear_aligner_target_version_id
 AND new_product.product_code = old_product.product_code
LEFT JOIN catalog_product_variant_v2 old_variant ON old_variant.variant_id = old_rule.variant_id
LEFT JOIN catalog_product_variant_v2 new_variant
  ON new_variant.config_version_id = @clear_aligner_target_version_id
 AND new_variant.variant_code = old_variant.variant_code
WHERE old_rule.config_version_id = @clear_aligner_source_version_id;

INSERT INTO catalog_alias_v2
    (config_version_id, canonical_type, canonical_id,
     alias_text, normalized_alias, status)
SELECT @clear_aligner_target_version_id, old_alias.canonical_type,
       new_product.product_id, old_alias.alias_text,
       old_alias.normalized_alias, old_alias.status
FROM catalog_alias_v2 old_alias
JOIN catalog_product_v2 old_product ON old_product.product_id = old_alias.canonical_id
JOIN catalog_product_v2 new_product
  ON new_product.config_version_id = @clear_aligner_target_version_id
 AND new_product.product_code = old_product.product_code
WHERE old_alias.config_version_id = @clear_aligner_source_version_id
  AND old_alias.canonical_type = 'PRODUCT';

INSERT INTO catalog_alias_v2
    (config_version_id, canonical_type, canonical_id,
     alias_text, normalized_alias, status)
SELECT @clear_aligner_target_version_id, old_alias.canonical_type,
       new_variant.variant_id, old_alias.alias_text,
       old_alias.normalized_alias, old_alias.status
FROM catalog_alias_v2 old_alias
JOIN catalog_product_variant_v2 old_variant ON old_variant.variant_id = old_alias.canonical_id
JOIN catalog_product_variant_v2 new_variant
  ON new_variant.config_version_id = @clear_aligner_target_version_id
 AND new_variant.variant_code = old_variant.variant_code
WHERE old_alias.config_version_id = @clear_aligner_source_version_id
  AND old_alias.canonical_type = 'PRODUCT_VARIANT';

INSERT INTO catalog_alias_v2
    (config_version_id, canonical_type, canonical_id,
     alias_text, normalized_alias, status)
SELECT @clear_aligner_target_version_id, old_alias.canonical_type,
       new_material.material_id, old_alias.alias_text,
       old_alias.normalized_alias, old_alias.status
FROM catalog_alias_v2 old_alias
JOIN catalog_material_v2 old_material ON old_material.material_id = old_alias.canonical_id
JOIN catalog_material_v2 new_material
  ON new_material.config_version_id = @clear_aligner_target_version_id
 AND new_material.material_code = old_material.material_code
WHERE old_alias.config_version_id = @clear_aligner_source_version_id
  AND old_alias.canonical_type = 'MATERIAL';

INSERT INTO catalog_alias_v2
    (config_version_id, canonical_type, canonical_id,
     alias_text, normalized_alias, status)
SELECT @clear_aligner_target_version_id, old_alias.canonical_type,
       new_accessory.accessory_id, old_alias.alias_text,
       old_alias.normalized_alias, old_alias.status
FROM catalog_alias_v2 old_alias
JOIN catalog_accessory_v2 old_accessory ON old_accessory.accessory_id = old_alias.canonical_id
JOIN catalog_accessory_v2 new_accessory
  ON new_accessory.config_version_id = @clear_aligner_target_version_id
 AND new_accessory.accessory_code = old_accessory.accessory_code
WHERE old_alias.config_version_id = @clear_aligner_source_version_id
  AND old_alias.canonical_type = 'ACCESSORY';

-- A locally maintained catalog may have omitted the previously empty category.
-- Ensure the confirmed category exists before inserting the newly orderable item.
INSERT INTO catalog_category_v2 (
    config_version_id, category_code, display_name, sort_order, status
)
VALUES (
    @clear_aligner_target_version_id, 'CLEAR_ALIGNER', '隐形正畸', 50, 'ACTIVE'
)
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE';

INSERT INTO catalog_product_v2 (
    config_version_id, category_id, product_code, display_name,
    workflow_product_type, tooth_rule_code,
    pricing_status, base_price_cents, currency, sort_order, status
)
VALUES (
    @clear_aligner_target_version_id,
    (SELECT category_id FROM catalog_category_v2
     WHERE config_version_id = @clear_aligner_target_version_id
       AND category_code = 'CLEAR_ALIGNER'),
    'CLEAR_ALIGNER_BRACELESS', '无托槽隐形矫治器',
    'ORTHODONTICS', 'TOOTH_CLEAR_ALIGNER',
    'PENDING_QUOTE', NULL, 'CNY', 950, 'ACTIVE'
);

SET @braceless_clear_aligner_product_id = LAST_INSERT_ID();

INSERT INTO catalog_rule_v2 (
    config_version_id, product_id, variant_id, rule_type,
    rule_code, rule_schema_json, sort_order, status
)
VALUES (
    @clear_aligner_target_version_id,
    @braceless_clear_aligner_product_id,
    NULL,
    'WORKFLOW',
    'CLEAR_ALIGNER_BRACELESS_WORKFLOW',
    JSON_OBJECT(
        'aligner_types', JSON_ARRAY(
            JSON_OBJECT('code', 'CLEAR_ALIGNER_BRACELESS', 'name', '无托槽隐形矫治器')
        ),
        'treatment_arches', JSON_ARRAY('FULL', 'UPPER', 'LOWER'),
        'treatment_modes', JSON_ARRAY('REGULAR', 'COMBINED')
    ),
    950,
    'ACTIVE'
);

UPDATE catalog_config_version
SET publication_status = 'INACTIVE',
    lock_version = lock_version + 1
WHERE publication_status = 'ACTIVE'
  AND config_version_id <> @clear_aligner_target_version_id;

UPDATE catalog_config_version
SET publication_status = 'ACTIVE',
    effective_at = CURRENT_TIMESTAMP(3),
    published_at = CURRENT_TIMESTAMP(3),
    lock_version = lock_version + 1
WHERE config_version_id = @clear_aligner_target_version_id;

INSERT INTO catalog_change_audit (
    config_version_id, entity_type, entity_id, action_type,
    after_value, reason
)
VALUES (
    @clear_aligner_target_version_id,
    'PRODUCT',
    @braceless_clear_aligner_product_id,
    'SOURCE_CORRECTION',
    JSON_OBJECT(
        'product_code', 'CLEAR_ALIGNER_BRACELESS',
        'display_name', '无托槽隐形矫治器',
        'status', 'ACTIVE'
    ),
    '按正畸平台医生端操作文档启用正式隐形正畸产品；保留原 A 型占位项为停用历史'
);
