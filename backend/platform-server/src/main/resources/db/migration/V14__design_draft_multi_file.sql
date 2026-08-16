CREATE TABLE design_draft_file (
    design_draft_file_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    design_draft_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_design_draft_file (design_draft_id, file_id),
    KEY idx_design_draft_file_draft (design_draft_id, sort_order),
    CONSTRAINT fk_design_draft_file_draft FOREIGN KEY (design_draft_id) REFERENCES design_draft (design_draft_id),
    CONSTRAINT fk_design_draft_file_resource FOREIGN KEY (file_id) REFERENCES file_resource (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO design_draft_file (design_draft_id, file_id, sort_order)
SELECT design_draft_id, file_id, 0
FROM design_draft
WHERE file_id IS NOT NULL;
