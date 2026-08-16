ALTER TABLE ai_audit_log
    ADD COLUMN prompt_version VARCHAR(64) NULL AFTER request_context_type,
    ADD KEY idx_ai_audit_prompt_version_created (prompt_version, created_at);
