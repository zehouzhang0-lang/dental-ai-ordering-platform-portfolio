ALTER TABLE auth_refresh_token
    ADD COLUMN family_id CHAR(36) NULL AFTER token_id;

-- 历史 token 不能被误并为同一会话：每条各自回填一个独立 family。
UPDATE auth_refresh_token
SET family_id = UUID()
WHERE family_id IS NULL;

ALTER TABLE auth_refresh_token
    MODIFY COLUMN family_id CHAR(36) NOT NULL;

CREATE INDEX idx_auth_refresh_token_family_active
    ON auth_refresh_token (family_id, revoked_at, expires_at);
