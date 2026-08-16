UPDATE workflow_node
SET need_in_check = 0,
    need_out_check = 0
WHERE process_name IN ('客服定基台', '客服核对订单信息及账单');

UPDATE order_process_node
SET need_in_check = 0,
    need_out_check = 0
WHERE process_name IN ('客服定基台', '客服核对订单信息及账单')
  AND node_status IN ('PENDING', 'READY');
