ALTER TABLE file_resource
    ADD COLUMN upload_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' AFTER file_size;

UPDATE file_resource
SET upload_status = 'COMPLETED'
WHERE status = 'ACTIVE'
  AND file_size IS NOT NULL;

ALTER TABLE file_resource
    ADD INDEX idx_file_resource_upload_status (upload_status),
    ADD INDEX idx_file_resource_owner_status (owner_user_id, upload_status);
