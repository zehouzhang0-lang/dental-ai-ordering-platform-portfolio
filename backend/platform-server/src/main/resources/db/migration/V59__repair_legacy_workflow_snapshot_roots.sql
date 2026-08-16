-- Repair process snapshots created before the design gate alignment.
-- Historical nodes are preserved for audit: obsolete intake/review roots are
-- marked as system-skipped instead of being deleted.

CREATE TEMPORARY TABLE legacy_workflow_instance (
    instance_id BIGINT PRIMARY KEY,
    chain_id BIGINT NOT NULL
);

INSERT INTO legacy_workflow_instance (instance_id, chain_id)
SELECT DISTINCT instance.instance_id, instance.chain_id
FROM order_process_instance instance
JOIN order_process_node legacy_node
  ON legacy_node.instance_id = instance.instance_id
WHERE instance.instance_status = 'ACTIVE'
  AND legacy_node.node_status IN ('PENDING', 'READY')
  AND (
      legacy_node.node_category = 'ORDER_INTAKE'
      OR (
          legacy_node.node_category = 'REVIEW'
          AND legacy_node.stage_name = '下单入厂'
      )
  );

INSERT IGNORE INTO order_process_node
    (instance_id, source_node_id, node_code, process_name, stage_name, step_order,
     is_optional, branch_group, branch_key, standard_duration, default_role,
     node_category, need_in_check, need_out_check, node_status, started_at, completed_at)
SELECT
    legacy.instance_id,
    gate_definition.node_id,
    gate_definition.node_code,
    gate_definition.process_name,
    gate_definition.stage_name,
    gate_definition.step_order,
    gate_definition.is_optional,
    gate_definition.branch_group,
    gate_definition.branch_key,
    gate_definition.standard_duration,
    gate_definition.default_role,
    gate_definition.node_category,
    gate_definition.need_in_check,
    gate_definition.need_out_check,
    CASE WHEN design_task.task_status = 'DOCTOR_CONFIRMED' THEN 'COMPLETED' ELSE 'READY' END,
    CASE WHEN design_task.task_status = 'DOCTOR_CONFIRMED' THEN CURRENT_TIMESTAMP(3) ELSE NULL END,
    CASE WHEN design_task.task_status = 'DOCTOR_CONFIRMED' THEN CURRENT_TIMESTAMP(3) ELSE NULL END
FROM legacy_workflow_instance legacy
JOIN workflow_node gate_definition
  ON gate_definition.chain_id = legacy.chain_id
 AND gate_definition.node_category = 'DESIGN_GATE'
JOIN order_process_instance instance
  ON instance.instance_id = legacy.instance_id
LEFT JOIN design_task
  ON design_task.order_id = instance.order_id
WHERE NOT EXISTS (
    SELECT 1
    FROM order_process_node existing_gate
    WHERE existing_gate.instance_id = legacy.instance_id
      AND existing_gate.node_category = 'DESIGN_GATE'
);

INSERT IGNORE INTO design_task (order_id, node_instance_id, task_status)
SELECT instance.order_id, gate_node.node_instance_id, 'OPEN'
FROM legacy_workflow_instance legacy
JOIN order_process_instance instance
  ON instance.instance_id = legacy.instance_id
JOIN order_process_node gate_node
  ON gate_node.instance_id = legacy.instance_id
 AND gate_node.node_category = 'DESIGN_GATE';

UPDATE design_task task
JOIN order_process_instance instance
  ON instance.order_id = task.order_id
JOIN legacy_workflow_instance legacy
  ON legacy.instance_id = instance.instance_id
JOIN order_process_node gate_node
  ON gate_node.instance_id = legacy.instance_id
 AND gate_node.node_category = 'DESIGN_GATE'
SET task.node_instance_id = gate_node.node_instance_id
WHERE task.node_instance_id IS NULL;

UPDATE order_process_node gate_node
JOIN legacy_workflow_instance legacy
  ON legacy.instance_id = gate_node.instance_id
JOIN order_process_instance instance
  ON instance.instance_id = legacy.instance_id
JOIN design_task task
  ON task.order_id = instance.order_id
SET gate_node.node_status = CASE
        WHEN task.task_status = 'DOCTOR_CONFIRMED' THEN 'COMPLETED'
        ELSE 'READY'
    END,
    gate_node.started_at = CASE
        WHEN task.task_status = 'DOCTOR_CONFIRMED'
        THEN COALESCE(gate_node.started_at, CURRENT_TIMESTAMP(3))
        ELSE gate_node.started_at
    END,
    gate_node.completed_at = CASE
        WHEN task.task_status = 'DOCTOR_CONFIRMED'
        THEN COALESCE(gate_node.completed_at, CURRENT_TIMESTAMP(3))
        ELSE NULL
    END
WHERE gate_node.node_category = 'DESIGN_GATE'
  AND gate_node.node_status IN ('PENDING', 'READY');

INSERT IGNORE INTO order_process_edge
    (instance_id, from_node_instance_id, to_node_instance_id, edge_type, condition_key)
SELECT
    legacy.instance_id,
    gate_node.node_instance_id,
    candidate.node_instance_id,
    'SEQUENCE',
    'DESIGN_DOCTOR_CONFIRMED'
FROM legacy_workflow_instance legacy
JOIN order_process_node gate_node
  ON gate_node.instance_id = legacy.instance_id
 AND gate_node.node_category = 'DESIGN_GATE'
JOIN order_process_node candidate
  ON candidate.instance_id = legacy.instance_id
 AND candidate.node_instance_id <> gate_node.node_instance_id
 AND candidate.node_category <> 'ORDER_INTAKE'
 AND NOT (
     candidate.node_category = 'REVIEW'
     AND candidate.stage_name = '下单入厂'
 )
WHERE NOT EXISTS (
    SELECT 1
    FROM order_process_edge incoming
    JOIN order_process_node predecessor
      ON predecessor.node_instance_id = incoming.from_node_instance_id
    WHERE incoming.instance_id = candidate.instance_id
      AND incoming.to_node_instance_id = candidate.node_instance_id
      AND predecessor.node_category <> 'ORDER_INTAKE'
      AND NOT (
          predecessor.node_category = 'REVIEW'
          AND predecessor.stage_name = '下单入厂'
      )
      AND predecessor.node_category <> 'DESIGN_GATE'
);

UPDATE order_process_node legacy_node
JOIN legacy_workflow_instance legacy
  ON legacy.instance_id = legacy_node.instance_id
SET legacy_node.node_status = 'SKIPPED',
    legacy_node.skipped_at = COALESCE(legacy_node.skipped_at, CURRENT_TIMESTAMP(3)),
    legacy_node.skip_reason = COALESCE(
        legacy_node.skip_reason,
        '系统修复：下单与审核节点不属于生产执行工序'
    )
WHERE legacy_node.node_status IN ('PENDING', 'READY')
  AND (
      legacy_node.node_category = 'ORDER_INTAKE'
      OR (
          legacy_node.node_category = 'REVIEW'
          AND legacy_node.stage_name = '下单入厂'
      )
  );

UPDATE order_process_node target
JOIN (
    SELECT ready_nodes.node_instance_id
    FROM (
        SELECT candidate.node_instance_id
        FROM order_process_node candidate
        JOIN legacy_workflow_instance legacy
          ON legacy.instance_id = candidate.instance_id
        WHERE candidate.node_status = 'PENDING'
          AND NOT EXISTS (
              SELECT 1
              FROM order_process_edge incoming
              JOIN order_process_node predecessor
                ON predecessor.node_instance_id = incoming.from_node_instance_id
              WHERE incoming.instance_id = candidate.instance_id
                AND incoming.to_node_instance_id = candidate.node_instance_id
                AND predecessor.node_status NOT IN ('COMPLETED', 'SKIPPED')
          )
    ) ready_nodes
) selected
  ON selected.node_instance_id = target.node_instance_id
SET target.node_status = 'READY';

DROP TEMPORARY TABLE legacy_workflow_instance;
