package com.yuri.aiorder.production;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ProductionEquipmentManagementTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void workerCanCreateEquipmentAndRegisterFaultEvent() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String equipmentCode = "EQ95_" + suffix;

        mockMvc.perform(post("/production/equipment")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", 950101L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "equipment_code": "%s",
                                  "equipment_name": "五轴切削机-%s",
                                  "equipment_type": "MILLING_MACHINE",
                                  "department_name": "生产部",
                                  "status": "RUNNING",
                                  "utilization_rate": 87.5
                                }
                                """.formatted(equipmentCode, suffix)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.equipment_code").value(equipmentCode))
                .andExpect(jsonPath("$.data.equipment_name").value("五轴切削机-" + suffix))
                .andExpect(jsonPath("$.data.equipment_type").value("MILLING_MACHINE"))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.owner_user_id").value(950101L))
                .andExpect(jsonPath("$.data.utilization_rate").value(87.5));

        mockMvc.perform(post("/production/equipment/{equipmentCode}/events", equipmentCode)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", 950101L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "event_type": "FAULT_REPAIR",
                                  "status": "IN_PROGRESS",
                                  "downtime_minutes": 45,
                                  "description": "主轴异常，等待维修确认"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.equipment_code").value(equipmentCode))
                .andExpect(jsonPath("$.data.event_type").value("FAULT_REPAIR"))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.downtime_minutes").value(45))
                .andExpect(jsonPath("$.data.description").value("主轴异常，等待维修确认"));

        mockMvc.perform(get("/production/equipment/summary")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 950102L)
                        .param("equipment_code_prefix", equipmentCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_equipment_count").value(1))
                .andExpect(jsonPath("$.data.running_count").value(1))
                .andExpect(jsonPath("$.data.open_fault_count").value(1))
                .andExpect(jsonPath("$.data.downtime_minutes").value(45));
    }

    @Test
    void doctorCannotCreateProductionEquipmentOrEvent() throws Exception {
        mockMvc.perform(post("/production/equipment")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", 950201L)
                        .header("X-Bootstrap-Clinic-Id", 12L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "equipment_code": "EQ95_DOC_BLOCK",
                                  "equipment_name": "医生端不可写设备",
                                  "equipment_type": "MILLING_MACHINE"
                                }
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/production/equipment/{equipmentCode}/events", "EQ95_DOC_BLOCK")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", 950201L)
                        .header("X-Bootstrap-Clinic-Id", 12L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "event_type": "FAULT_REPAIR",
                                  "status": "PENDING",
                                  "description": "医生端不可写事件"
                                }
                                """))
                .andExpect(status().isForbidden());
    }
}
