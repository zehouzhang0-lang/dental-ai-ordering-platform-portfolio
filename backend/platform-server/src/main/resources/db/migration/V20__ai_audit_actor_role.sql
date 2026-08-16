ALTER TABLE ai_audit_log
    ADD COLUMN actor_role VARCHAR(32) NULL AFTER actor_user_id,
    ADD KEY idx_ai_audit_role_status_created (actor_role, result_status, created_at);
