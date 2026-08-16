INSERT INTO form_field_config
    (product_type, field_key, field_label, field_type, options_json, required_flag, sort_order, status)
SELECT chain.product_type, 'patient_name', '患者姓名', 'text', NULL, 1, 10, 'ACTIVE'
FROM workflow_chain chain
WHERE chain.status = 1 AND chain.product_type <> 'REGULAR_CROWN'
ON DUPLICATE KEY UPDATE
    field_label = VALUES(field_label),
    field_type = VALUES(field_type),
    options_json = VALUES(options_json),
    required_flag = VALUES(required_flag),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO form_field_config
    (product_type, field_key, field_label, field_type, options_json, required_flag, sort_order, status)
SELECT chain.product_type, 'tooth_position', '牙位', 'text', NULL, 1, 20, 'ACTIVE'
FROM workflow_chain chain
WHERE chain.status = 1 AND chain.product_type <> 'REGULAR_CROWN'
ON DUPLICATE KEY UPDATE
    field_label = VALUES(field_label), field_type = VALUES(field_type), options_json = VALUES(options_json),
    required_flag = VALUES(required_flag), sort_order = VALUES(sort_order), status = VALUES(status);

INSERT INTO form_field_config
    (product_type, field_key, field_label, field_type, options_json, required_flag, sort_order, status)
SELECT chain.product_type, 'material', '材料', 'text', NULL, 0, 30, 'ACTIVE'
FROM workflow_chain chain
WHERE chain.status = 1 AND chain.product_type <> 'REGULAR_CROWN'
ON DUPLICATE KEY UPDATE
    field_label = VALUES(field_label), field_type = VALUES(field_type), options_json = VALUES(options_json),
    required_flag = VALUES(required_flag), sort_order = VALUES(sort_order), status = VALUES(status);

INSERT INTO form_field_config
    (product_type, field_key, field_label, field_type, options_json, required_flag, sort_order, status)
SELECT chain.product_type, 'shade', '色号', 'text', NULL, 0, 40, 'ACTIVE'
FROM workflow_chain chain
WHERE chain.status = 1 AND chain.product_type <> 'REGULAR_CROWN'
ON DUPLICATE KEY UPDATE
    field_label = VALUES(field_label), field_type = VALUES(field_type), options_json = VALUES(options_json),
    required_flag = VALUES(required_flag), sort_order = VALUES(sort_order), status = VALUES(status);

INSERT INTO form_field_config
    (product_type, field_key, field_label, field_type, options_json, required_flag, sort_order, status)
SELECT chain.product_type, 'doctor_note', '医生备注', 'textarea', NULL, 0, 50, 'ACTIVE'
FROM workflow_chain chain
WHERE chain.status = 1 AND chain.product_type <> 'REGULAR_CROWN'
ON DUPLICATE KEY UPDATE
    field_label = VALUES(field_label), field_type = VALUES(field_type), options_json = VALUES(options_json),
    required_flag = VALUES(required_flag), sort_order = VALUES(sort_order), status = VALUES(status);
