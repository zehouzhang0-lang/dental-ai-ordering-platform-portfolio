INSERT INTO form_field_config
    (product_type, field_key, field_label, field_type, options_json, required_flag, sort_order, status)
VALUES
    ('REGULAR_CROWN', 'patient_name', '患者姓名', 'text', NULL, 1, 10, 'ACTIVE'),
    ('REGULAR_CROWN', 'tooth_position', '牙位', 'text', NULL, 1, 20, 'ACTIVE'),
    ('REGULAR_CROWN', 'material', '材料', 'select', JSON_ARRAY('氧化锆', '玻璃陶瓷', '钴铬合金'), 0, 30, 'ACTIVE'),
    ('REGULAR_CROWN', 'shade', '色号', 'text', NULL, 0, 40, 'ACTIVE'),
    ('REGULAR_CROWN', 'doctor_note', '医生备注', 'textarea', NULL, 0, 50, 'ACTIVE')
ON DUPLICATE KEY UPDATE
    field_label = VALUES(field_label),
    field_type = VALUES(field_type),
    options_json = VALUES(options_json),
    required_flag = VALUES(required_flag),
    sort_order = VALUES(sort_order),
    status = VALUES(status);
