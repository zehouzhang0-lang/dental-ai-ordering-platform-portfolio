CREATE TABLE order_message_mention (
    message_id BIGINT NOT NULL,
    mentioned_user_id BIGINT NOT NULL,
    resolved_at DATETIME(3) NULL,
    PRIMARY KEY (message_id, mentioned_user_id),
    KEY idx_order_message_mention_user_open (mentioned_user_id, resolved_at),
    CONSTRAINT fk_order_message_mention_message
        FOREIGN KEY (message_id) REFERENCES order_message (message_id),
    CONSTRAINT fk_order_message_mention_user
        FOREIGN KEY (mentioned_user_id) REFERENCES system_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
