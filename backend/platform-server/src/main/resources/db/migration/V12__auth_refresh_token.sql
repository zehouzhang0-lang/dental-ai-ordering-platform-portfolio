CREATE TABLE IF NOT EXISTS auth_refresh_token (
    token_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    token_hash CHAR(64) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3) NOT NULL,
    last_used_at DATETIME(3) NULL,
    revoked_at DATETIME(3) NULL,
    CONSTRAINT fk_auth_refresh_token_user
        FOREIGN KEY (user_id) REFERENCES system_user(user_id)
);

CREATE INDEX idx_auth_refresh_token_user_active
    ON auth_refresh_token (user_id, revoked_at, expires_at);
