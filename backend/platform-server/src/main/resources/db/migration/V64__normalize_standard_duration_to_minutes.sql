-- D-175 统一标准工时单位为分钟。旧代码按秒写入/读取，但 deadline 逻辑已按分钟使用，
-- 因此在标准工时维护中心启用前一次性把既有非空值换算为等价分钟。
UPDATE workflow_node
SET standard_duration = CEIL(standard_duration / 60.0)
WHERE standard_duration IS NOT NULL;

UPDATE order_process_node
SET standard_duration = CEIL(standard_duration / 60.0)
WHERE standard_duration IS NOT NULL;
