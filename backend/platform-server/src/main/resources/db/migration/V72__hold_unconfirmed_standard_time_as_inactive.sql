-- 客户尚未提供正式标准工时。保留版本、明细与审计，不删除浏览器验收数据；
-- 所有既有 ACTIVE 版本先转为 INACTIVE，待客户正式数据确认并显式开启运行时开关后再发布。
INSERT INTO workflow_standard_time_audit
    (standard_time_version_id, action_type, before_value, after_value, reason)
SELECT
    version.standard_time_version_id,
    'HOLD_UNCONFIRMED',
    JSON_OBJECT(
        'publication_status', version.publication_status,
        'version_no', version.version_no,
        'version_name', version.version_name
    ),
    JSON_OBJECT(
        'publication_status', 'INACTIVE',
        'version_no', version.version_no,
        'version_name', version.version_name
    ),
    '客户尚未提供正式标准工时；现有分钟值仅为浏览器验收数据'
FROM workflow_standard_time_version version
WHERE version.publication_status = 'ACTIVE';

UPDATE workflow_standard_time_version
SET publication_status = 'INACTIVE',
    lock_version = lock_version + 1
WHERE publication_status = 'ACTIVE';

UPDATE system_menu
SET status = 'INACTIVE'
WHERE menu_code = 'workflow-standard-time';
