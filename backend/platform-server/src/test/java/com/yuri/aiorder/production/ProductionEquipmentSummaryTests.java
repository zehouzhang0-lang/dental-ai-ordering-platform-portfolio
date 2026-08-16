package com.yuri.aiorder.production;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ProductionEquipmentSummaryTests {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void productionEquipmentSummaryAggregatesStatusMaintenanceFaultAndUtilization() throws Exception {
        String prefix = "EQ_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long runningEquipmentId = createEquipment(prefix + "_RUN", "切削设备", "RUNNING", 92.5);
        createEquipment(prefix + "_IDLE", "扫描设备", "IDLE", 30.0);
        long maintenanceEquipmentId = createEquipment(prefix + "_MAIN", "烧结炉", "MAINTENANCE", 40.0);
        long faultEquipmentId = createEquipment(prefix + "_FAULT", "3D 打印机", "FAULT", 0.0);

        createEvent(maintenanceEquipmentId, "MAINTENANCE_PLAN", "PENDING", 0);
        createEvent(faultEquipmentId, "FAULT_REPAIR", "IN_PROGRESS", 120);
        createEvent(runningEquipmentId, "DOWNTIME", "DONE", 60);

        mockMvc.perform(get("/production/equipment/summary")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", 990200001L)
                        .param("equipment_code_prefix", prefix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.equipment_code_prefix").value(prefix))
                .andExpect(jsonPath("$.data.total_equipment_count").value(4))
                .andExpect(jsonPath("$.data.running_count").value(1))
                .andExpect(jsonPath("$.data.idle_count").value(1))
                .andExpect(jsonPath("$.data.maintenance_count").value(1))
                .andExpect(jsonPath("$.data.fault_count").value(1))
                .andExpect(jsonPath("$.data.pending_maintenance_count").value(1))
                .andExpect(jsonPath("$.data.open_fault_count").value(1))
                .andExpect(jsonPath("$.data.downtime_minutes").value(180))
                .andExpect(jsonPath("$.data.average_utilization_rate").value(40.6));
    }

    @Test
    void doctorCannotReadProductionEquipmentSummary() throws Exception {
        mockMvc.perform(get("/production/equipment/summary")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", 990100001L))
                .andExpect(status().isForbidden());
    }

    private long createEquipment(String equipmentCode, String equipmentName, String status, double utilizationRate) {
        jdbcClient.sql("""
                        INSERT INTO production_equipment
                            (equipment_code, equipment_name, equipment_type, department_name, status, utilization_rate)
                        VALUES
                            (:equipmentCode, :equipmentName, 'DENTAL_DEVICE', '生产部', :status, :utilizationRate)
                        """)
                .param("equipmentCode", equipmentCode)
                .param("equipmentName", equipmentName)
                .param("status", status)
                .param("utilizationRate", utilizationRate)
                .update();
        return jdbcClient.sql("SELECT equipment_id FROM production_equipment WHERE equipment_code = :equipmentCode")
                .param("equipmentCode", equipmentCode)
                .query(Long.class)
                .single();
    }

    private void createEvent(long equipmentId, String eventType, String status, int downtimeMinutes) {
        jdbcClient.sql("""
                        INSERT INTO production_equipment_event
                            (equipment_id, event_type, status, downtime_minutes, description)
                        VALUES
                            (:equipmentId, :eventType, :status, :downtimeMinutes, '设备汇总测试')
                        """)
                .param("equipmentId", equipmentId)
                .param("eventType", eventType)
                .param("status", status)
                .param("downtimeMinutes", downtimeMinutes)
                .update();
    }
}
