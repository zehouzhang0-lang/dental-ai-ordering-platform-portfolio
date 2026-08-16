ALTER TABLE file_resource
    ADD COLUMN upload_mode VARCHAR(32) NOT NULL DEFAULT 'SINGLE' AFTER upload_status,
    ADD COLUMN multipart_upload_id VARCHAR(256) NULL AFTER upload_mode,
    ADD COLUMN multipart_part_size BIGINT NULL AFTER multipart_upload_id,
    ADD COLUMN multipart_part_count INT NULL AFTER multipart_part_size,
    ADD KEY idx_file_resource_multipart_upload (multipart_upload_id);
