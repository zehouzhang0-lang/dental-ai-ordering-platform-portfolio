ALTER TABLE design_draft
    ADD COLUMN cs_reject_reason VARCHAR(500) NULL AFTER draft_status,
    ADD COLUMN doctor_reject_reason VARCHAR(500) NULL AFTER cs_reject_reason;
