ALTER TABLE workflow_standard_time_item
    DROP CHECK chk_workflow_standard_time_minutes;

ALTER TABLE workflow_standard_time_item
    ADD CONSTRAINT chk_workflow_standard_time_minutes
        CHECK (standard_duration_minutes IS NULL
            OR (standard_duration_minutes >= 0 AND standard_duration_minutes <= 43200));
