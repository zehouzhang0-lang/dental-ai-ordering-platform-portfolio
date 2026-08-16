-- Branch-specific template nodes are omitted from an order snapshot. Preserve the
-- dependency between the nearest retained nodes on both sides of an omitted path.
INSERT IGNORE INTO order_process_edge
    (instance_id, from_node_instance_id, to_node_instance_id, edge_type, condition_key)
WITH RECURSIVE projected_path AS (
    SELECT
        instance.instance_id,
        retained_from.node_instance_id AS from_node_instance_id,
        definition_edge.to_node_id AS current_source_node_id,
        definition_edge.edge_type,
        definition_edge.condition_key,
        1 AS path_depth
    FROM order_process_instance instance
    JOIN order_process_node retained_from
      ON retained_from.instance_id = instance.instance_id
    JOIN workflow_edge definition_edge
      ON definition_edge.chain_id = instance.chain_id
     AND definition_edge.from_node_id = retained_from.source_node_id

    UNION ALL

    SELECT
        projected_path.instance_id,
        projected_path.from_node_instance_id,
        definition_edge.to_node_id AS current_source_node_id,
        definition_edge.edge_type,
        definition_edge.condition_key,
        projected_path.path_depth + 1
    FROM projected_path
    JOIN order_process_instance instance
      ON instance.instance_id = projected_path.instance_id
    LEFT JOIN order_process_node retained_current
      ON retained_current.instance_id = projected_path.instance_id
     AND retained_current.source_node_id = projected_path.current_source_node_id
    JOIN workflow_edge definition_edge
      ON definition_edge.chain_id = instance.chain_id
     AND definition_edge.from_node_id = projected_path.current_source_node_id
    WHERE retained_current.node_instance_id IS NULL
      AND projected_path.path_depth < 100
)
SELECT
    projected_path.instance_id,
    projected_path.from_node_instance_id,
    retained_target.node_instance_id,
    projected_path.edge_type,
    projected_path.condition_key
FROM projected_path
JOIN order_process_node retained_target
  ON retained_target.instance_id = projected_path.instance_id
 AND retained_target.source_node_id = projected_path.current_source_node_id
WHERE retained_target.node_instance_id <> projected_path.from_node_instance_id;

-- A downstream node may have been marked READY only because its incoming path was
-- missing. Demote only READY nodes; never rewrite started or completed history.
UPDATE order_process_node candidate
JOIN order_process_edge incoming
  ON incoming.instance_id = candidate.instance_id
 AND incoming.to_node_instance_id = candidate.node_instance_id
JOIN order_process_node predecessor
  ON predecessor.node_instance_id = incoming.from_node_instance_id
LEFT JOIN check_record passed_out_check
  ON passed_out_check.node_instance_id = predecessor.node_instance_id
 AND passed_out_check.check_type = 'OUT'
 AND passed_out_check.result = 'PASS'
SET candidate.node_status = 'PENDING'
WHERE candidate.node_status = 'READY'
  AND (
      predecessor.node_status NOT IN ('COMPLETED', 'SKIPPED')
      OR (predecessor.need_out_check = 1 AND passed_out_check.check_id IS NULL)
  );
