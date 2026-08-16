UPDATE order_process_node
SET deadline_at = DATE_ADD(started_at, INTERVAL COALESCE(standard_duration, 0) MINUTE)
WHERE deadline_at IS NULL
  AND started_at IS NOT NULL;
