ALTER TABLE ai_audit_log
    ADD COLUMN estimated_cost_microusd BIGINT NULL AFTER output_token_count;
