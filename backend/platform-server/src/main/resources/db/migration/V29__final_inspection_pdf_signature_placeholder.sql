ALTER TABLE final_inspection_report
    ADD COLUMN pdf_file_id BIGINT NULL AFTER summary,
    ADD COLUMN signature_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' AFTER status,
    ADD COLUMN signed_by_user_id BIGINT NULL AFTER signature_status,
    ADD COLUMN signed_at DATETIME NULL AFTER signed_by_user_id,
    ADD KEY idx_final_inspection_report_pdf_file (pdf_file_id),
    ADD CONSTRAINT fk_final_inspection_report_pdf_file FOREIGN KEY (pdf_file_id) REFERENCES file_resource (file_id);
