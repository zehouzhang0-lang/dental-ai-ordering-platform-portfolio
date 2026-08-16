-- 用户确认先按现有《生产流程》预留工序工时填写入口。
-- 这里只恢复草稿维护菜单并确保存在一个可填写的空值草稿；
-- 正式发布、实例快照、截止时间和绩效计算仍由
-- WORKFLOW_STANDARD_TIME_FORMAL_ENABLED 显式控制。
UPDATE system_menu
SET menu_name = '工序工时设置',
    route_path = '/admin/workflow/standard-time',
    component_path = 'WorkflowStandardTimeView',
    permission_code = 'workflow:standard-time:manage',
    status = 'ACTIVE'
WHERE menu_code = 'workflow-standard-time';

SET @next_standard_time_version_no = (
    SELECT COALESCE(MAX(version_no), 0) + 1
    FROM workflow_standard_time_version
);

INSERT INTO workflow_standard_time_version
    (version_no, version_name, publication_status)
SELECT
    @next_standard_time_version_no,
    '工序工时待设置草稿 2026-08-01',
    'DRAFT'
WHERE NOT EXISTS (
    SELECT 1
    FROM workflow_standard_time_version
    WHERE publication_status = 'DRAFT'
);

SET @standard_time_draft_id = (
    SELECT standard_time_version_id
    FROM workflow_standard_time_version
    WHERE publication_status = 'DRAFT'
    ORDER BY version_no DESC
    LIMIT 1
);

INSERT INTO workflow_standard_time_item
    (standard_time_version_id, chain_id, node_id, standard_duration_minutes, status)
SELECT
    @standard_time_draft_id,
    node.chain_id,
    node.node_id,
    NULL,
    'ACTIVE'
FROM workflow_node node
WHERE @standard_time_draft_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM workflow_standard_time_item existing
      WHERE existing.standard_time_version_id = @standard_time_draft_id
        AND existing.node_id = node.node_id
  );
