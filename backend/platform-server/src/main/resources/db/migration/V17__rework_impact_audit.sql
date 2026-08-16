ALTER TABLE rework_record
    ADD COLUMN impacted_node_count INT NOT NULL DEFAULT 0 AFTER target_node_instance_id,
    ADD COLUMN impacted_node_instance_ids JSON NULL AFTER impacted_node_count;
