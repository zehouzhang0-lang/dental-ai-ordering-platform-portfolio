-- Authoritative source:
-- .local-context/生产流程.docx
-- SHA-256: 1db603cfbb588a5bcd84eab1d63a44febe382813bf0af36fc8dcde9191d4259c
--
-- The original V1 templates linearized branches by step_order and treated every
-- chain as an intake choice. Three templates therefore need a new version:
-- 1. Implant: both intake routes must join before 种植入货检, and only the
--    custom-abutment route may execute the CAD/custom-abutment fabrication steps.
-- 2. Veneer: each fabrication route owns its own 上釉/抛光/质检出货 tail.
-- 3. Precision attachment: the document defines one linear route and no
--    impression/scan choice, so production review must not invent one.
--
-- Existing order_process_* snapshots are intentionally untouched.

INSERT INTO workflow_chain
    (chain_code, chain_name, product_type, version, intake_branch, status)
SELECT
    source.chain_code,
    source.chain_name,
    source.product_type,
    2,
    CASE
        WHEN source.chain_code = 'PRECISION_ATTACHMENT' THEN 'NONE'
        ELSE source.intake_branch
    END,
    1
FROM workflow_chain source
WHERE source.chain_code IN (
    'IMPLANT_RESTORATION',
    'PRECISION_ATTACHMENT',
    'VENEER_RESTORATION'
)
  AND source.version = 1
  AND NOT EXISTS (
      SELECT 1
      FROM workflow_chain existing
      WHERE existing.chain_code = source.chain_code
        AND existing.version = 2
  );

INSERT INTO workflow_node
    (chain_id, node_code, process_name, stage_name, step_order, is_optional,
     branch_group, branch_key, standard_duration, default_role, node_category,
     need_in_check, need_out_check)
SELECT
    target.chain_id,
    source_node.node_code,
    source_node.process_name,
    source_node.stage_name,
    source_node.step_order,
    source_node.is_optional,
    source_node.branch_group,
    source_node.branch_key,
    source_node.standard_duration,
    source_node.default_role,
    source_node.node_category,
    source_node.need_in_check,
    source_node.need_out_check
FROM workflow_chain source
JOIN workflow_node source_node
  ON source_node.chain_id = source.chain_id
JOIN workflow_chain target
  ON target.chain_code = source.chain_code
 AND target.version = 2
WHERE source.chain_code IN (
    'IMPLANT_RESTORATION',
    'PRECISION_ATTACHMENT',
    'VENEER_RESTORATION'
)
  AND source.version = 1
  AND source_node.node_category <> 'DESIGN_GATE'
  AND NOT EXISTS (
      SELECT 1
      FROM workflow_node existing
      WHERE existing.chain_id = target.chain_id
        AND existing.node_code = source_node.node_code
  );

-- Standard-time versioning was introduced after the original workflow seed.
-- When that table exists, carry every version's value to the corresponding V2
-- node by stable node_code. On older schemas this statement is a no-op.
SET @standard_time_table_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'workflow_standard_time_item'
);
SET @copy_standard_time_sql = IF(
    @standard_time_table_exists = 1,
    'INSERT INTO workflow_standard_time_item
        (standard_time_version_id, chain_id, node_id, standard_duration_minutes, status)
     SELECT
        source_item.standard_time_version_id,
        target_chain.chain_id,
        target_node.node_id,
        source_item.standard_duration_minutes,
        source_item.status
     FROM workflow_standard_time_item source_item
     JOIN workflow_node source_node
       ON source_node.node_id = source_item.node_id
     JOIN workflow_chain source_chain
       ON source_chain.chain_id = source_node.chain_id
     JOIN workflow_chain target_chain
       ON target_chain.chain_code = source_chain.chain_code
      AND target_chain.version = 2
     JOIN workflow_node target_node
       ON target_node.chain_id = target_chain.chain_id
      AND target_node.node_code = source_node.node_code
     WHERE source_chain.version = 1
       AND NOT EXISTS (
           SELECT 1
           FROM workflow_standard_time_item existing
           WHERE existing.standard_time_version_id = source_item.standard_time_version_id
             AND existing.node_id = target_node.node_id
       )',
    'SELECT 1'
);
PREPARE copy_standard_time_statement FROM @copy_standard_time_sql;
EXECUTE copy_standard_time_statement;
DEALLOCATE PREPARE copy_standard_time_statement;

-- Source layout:
-- 收发出货 -> {印模... | 口扫...} -> 种植入货检 -> 种植配基台
-- -> {成品基台... | 个性化基台...} -> 种植上部冠设计
UPDATE workflow_node node
JOIN workflow_chain chain
  ON chain.chain_id = node.chain_id
SET node.step_order = CASE node.node_code
        WHEN 'IMPLANT_RESTORATION_0090' THEN 120
        WHEN 'IMPLANT_RESTORATION_0100' THEN 90
        WHEN 'IMPLANT_RESTORATION_0110' THEN 100
        WHEN 'IMPLANT_RESTORATION_0120' THEN 110
        WHEN 'IMPLANT_RESTORATION_0150' THEN 130
        WHEN 'IMPLANT_RESTORATION_0130' THEN 140
        WHEN 'IMPLANT_RESTORATION_0140' THEN 150
        ELSE node.step_order
    END,
    node.branch_group = CASE
        WHEN node.node_code IN (
            'IMPLANT_RESTORATION_0170',
            'IMPLANT_RESTORATION_0180',
            'IMPLANT_RESTORATION_0190',
            'IMPLANT_RESTORATION_0200'
        ) THEN 'implant_abutment'
        ELSE node.branch_group
    END,
    node.branch_key = CASE
        WHEN node.node_code IN (
            'IMPLANT_RESTORATION_0170',
            'IMPLANT_RESTORATION_0180',
            'IMPLANT_RESTORATION_0190',
            'IMPLANT_RESTORATION_0200'
        ) THEN 'CUSTOM_ABUTMENT'
        ELSE node.branch_key
    END
WHERE chain.chain_code = 'IMPLANT_RESTORATION'
  AND chain.version = 2;

-- Each veneer route has its own finishing tail before both routes join at 等待出货.
UPDATE workflow_node node
JOIN workflow_chain chain
  ON chain.chain_id = node.chain_id
SET node.branch_group = 'veneer_route',
    node.branch_key = CASE
        WHEN node.node_code IN (
            'VENEER_RESTORATION_0240',
            'VENEER_RESTORATION_0250',
            'VENEER_RESTORATION_0260'
        ) THEN 'CAD_MILLING'
        ELSE 'TRADITIONAL_WAX'
    END,
    node.stage_name = '贴面路线'
WHERE chain.chain_code = 'VENEER_RESTORATION'
  AND chain.version = 2
  AND node.node_code IN (
      'VENEER_RESTORATION_0240',
      'VENEER_RESTORATION_0250',
      'VENEER_RESTORATION_0260',
      'VENEER_RESTORATION_0370',
      'VENEER_RESTORATION_0380',
      'VENEER_RESTORATION_0390'
  );

-- Rebuild only the V2 definition edges. Order snapshots reference their own
-- copied edges, so historical and in-flight orders are not changed.
DELETE edge
FROM workflow_edge edge
JOIN workflow_chain chain
  ON chain.chain_id = edge.chain_id
WHERE chain.chain_code IN (
    'IMPLANT_RESTORATION',
    'PRECISION_ATTACHMENT',
    'VENEER_RESTORATION'
)
  AND chain.version = 2;

INSERT INTO workflow_edge
    (chain_id, from_node_id, to_node_id, edge_type, condition_key)
SELECT
    from_node.chain_id,
    from_node.node_id,
    to_node.node_id,
    CASE WHEN to_node.branch_group IS NULL THEN 'SEQUENCE' ELSE 'BRANCH' END,
    to_node.branch_key
FROM workflow_node from_node
JOIN workflow_chain chain
  ON chain.chain_id = from_node.chain_id
JOIN workflow_node to_node
  ON to_node.chain_id = from_node.chain_id
WHERE chain.chain_code IN (
    'IMPLANT_RESTORATION',
    'PRECISION_ATTACHMENT',
    'VENEER_RESTORATION'
)
  AND chain.version = 2
  AND from_node.node_category <> 'DESIGN_GATE'
  AND to_node.node_category <> 'DESIGN_GATE'
  AND to_node.step_order = (
      SELECT MIN(next_node.step_order)
      FROM workflow_node next_node
      WHERE next_node.chain_id = from_node.chain_id
        AND next_node.node_category <> 'DESIGN_GATE'
        AND next_node.step_order > from_node.step_order
  );

UPDATE workflow_chain
SET status = 0
WHERE chain_code IN (
    'IMPLANT_RESTORATION',
    'PRECISION_ATTACHMENT',
    'VENEER_RESTORATION'
)
  AND version < 2;

UPDATE workflow_chain
SET status = 1
WHERE chain_code IN (
    'IMPLANT_RESTORATION',
    'PRECISION_ATTACHMENT',
    'VENEER_RESTORATION'
)
  AND version = 2;
