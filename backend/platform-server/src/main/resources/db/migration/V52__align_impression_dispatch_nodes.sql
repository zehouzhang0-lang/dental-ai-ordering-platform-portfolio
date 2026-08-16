-- The source production-flow document places these dispatch/check nodes at the
-- end of the physical-impression branch. They must not be instantiated for SCAN.
UPDATE workflow_node
SET branch_group = 'intake',
    branch_key = 'IMPRESSION'
WHERE node_code IN (
    'REGULAR_CROWN_0090',
    'VENEER_RESTORATION_0080',
    'REMOVABLE_STEEL_0080',
    'REMOVABLE_ACRYLIC_0090',
    'REMOVABLE_INVISIBLE_0090',
    'ORTHODONTICS_0090'
);

UPDATE order_process_node
SET branch_group = 'intake',
    branch_key = 'IMPRESSION'
WHERE node_code IN (
    'REGULAR_CROWN_0090',
    'VENEER_RESTORATION_0080',
    'REMOVABLE_STEEL_0080',
    'REMOVABLE_ACRYLIC_0090',
    'REMOVABLE_INVISIBLE_0090',
    'ORTHODONTICS_0090'
);

-- Existing SCAN snapshots retain the historical row for audit, but the
-- incorrectly included node is resolved as a system skip when it never started.
UPDATE order_process_node node
JOIN order_process_instance instance
  ON instance.instance_id = node.instance_id
SET node.node_status = 'SKIPPED',
    node.skipped_at = CURRENT_TIMESTAMP(3),
    node.skip_reason = '系统校准：口扫路线不执行印模收发出货节点'
WHERE instance.intake_branch_used = 'SCAN'
  AND node.branch_group = 'intake'
  AND node.branch_key = 'IMPRESSION'
  AND node.node_status IN ('PENDING', 'READY')
  AND node.started_at IS NULL
  AND node.completed_at IS NULL;

-- Add the direct dependency that the corrected SCAN path would have received
-- when instantiated from the aligned template. Existing edges stay as audit
-- evidence; INSERT IGNORE only supplies the missing effective dependency.
INSERT IGNORE INTO order_process_edge
    (instance_id, from_node_instance_id, to_node_instance_id, edge_type, condition_key)
SELECT
    instance.instance_id,
    predecessor.node_instance_id,
    scan_entry.node_instance_id,
    'SEQUENCE',
    NULL
FROM order_process_instance instance
JOIN order_process_node predecessor
  ON predecessor.instance_id = instance.instance_id
JOIN order_process_node scan_entry
  ON scan_entry.instance_id = instance.instance_id
WHERE instance.intake_branch_used = 'SCAN'
  AND (
      (predecessor.node_code = 'REGULAR_CROWN_0040' AND scan_entry.node_code = 'REGULAR_CROWN_0100')
      OR (predecessor.node_code = 'VENEER_RESTORATION_0040' AND scan_entry.node_code = 'VENEER_RESTORATION_0090')
      OR (predecessor.node_code = 'REMOVABLE_STEEL_0040' AND scan_entry.node_code = 'REMOVABLE_STEEL_0090')
      OR (predecessor.node_code = 'REMOVABLE_ACRYLIC_0040' AND scan_entry.node_code = 'REMOVABLE_ACRYLIC_0100')
      OR (predecessor.node_code = 'REMOVABLE_INVISIBLE_0040' AND scan_entry.node_code = 'REMOVABLE_INVISIBLE_0100')
      OR (predecessor.node_code = 'ORTHODONTICS_0040' AND scan_entry.node_code = 'ORTHODONTICS_0100')
  );
