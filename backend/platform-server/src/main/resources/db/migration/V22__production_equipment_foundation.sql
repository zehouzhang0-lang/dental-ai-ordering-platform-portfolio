CREATE TABLE production_equipment (
    equipment_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    equipment_code VARCHAR(64) NOT NULL,
    equipment_name VARCHAR(128) NOT NULL,
    equipment_type VARCHAR(64) NOT NULL,
    department_name VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'IDLE',
    owner_user_id BIGINT NULL,
    utilization_rate DECIMAL(5, 1) NOT NULL DEFAULT 0.0,
    last_maintenance_at DATETIME(3) NULL,
    next_maintenance_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_production_equipment_code (equipment_code),
    KEY idx_production_equipment_status (status),
    KEY idx_production_equipment_department (department_name)
);

CREATE TABLE production_equipment_event (
    event_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    equipment_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    downtime_minutes INT NOT NULL DEFAULT 0,
    description VARCHAR(512) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    resolved_at DATETIME(3) NULL,
    KEY idx_equipment_event_equipment (equipment_id),
    KEY idx_equipment_event_type_status (event_type, status),
    CONSTRAINT fk_equipment_event_equipment
        FOREIGN KEY (equipment_id) REFERENCES production_equipment(equipment_id)
);
