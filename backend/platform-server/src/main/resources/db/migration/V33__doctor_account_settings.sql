ALTER TABLE system_user
    ADD COLUMN contact_email VARCHAR(128) NULL AFTER display_name,
    ADD COLUMN contact_phone VARCHAR(32) NULL AFTER contact_email,
    ADD COLUMN shipping_address VARCHAR(512) NULL AFTER contact_phone,
    ADD COLUMN notification_push_enabled TINYINT(1) NOT NULL DEFAULT 1 AFTER shipping_address;
