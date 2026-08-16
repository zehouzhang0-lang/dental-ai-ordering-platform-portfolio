CREATE TABLE final_inspection_report_file (
    report_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (report_id, file_id),
    CONSTRAINT fk_final_inspection_report_file_report FOREIGN KEY (report_id) REFERENCES final_inspection_report (report_id),
    CONSTRAINT fk_final_inspection_report_file_resource FOREIGN KEY (file_id) REFERENCES file_resource (file_id)
);

INSERT INTO system_permission (permission_code, permission_name, module_code, status)
SELECT 'final-inspection:manage', '终检报告管理', 'check', 'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM system_permission WHERE permission_code = 'final-inspection:manage'
);

INSERT INTO system_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM system_role r
JOIN system_permission p ON p.permission_code = 'final-inspection:manage'
LEFT JOIN system_role_permission existing
       ON existing.role_id = r.role_id
      AND existing.permission_id = p.permission_id
WHERE r.role_code IN ('ADMIN', 'WORKER')
  AND existing.role_id IS NULL;
