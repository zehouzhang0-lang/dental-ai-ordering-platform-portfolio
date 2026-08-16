package com.yuri.aiorder.production;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
class ProductionSafetyEnvironmentManagementTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void workerCanCreateSafetyEventAndCloseRectification() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String eventNo = "SAFE95_" + suffix;

        mockMvc.perform(post("/production/safety-environment/events")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", 953101L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "event_no": "%s",
                                  "event_type": "HAZARD_RECTIFICATION",
                                  "status": "PENDING",
                                  "department_name": "烧结车间",
                                  "responsible_owner": "安环员",
                                  "equipment_code": "FURNACE_%s",
                                  "risk_level": "HIGH",
                                  "due_at": "2026-07-10T18:00:00",
                                  "description": "炉区通道临时堆放物需整改"
                                }
                                """.formatted(eventNo, suffix)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.event_no").value(eventNo))
                .andExpect(jsonPath("$.data.event_type").value("HAZARD_RECTIFICATION"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.department_name").value("烧结车间"))
                .andExpect(jsonPath("$.data.responsible_owner").value("安环员"))
                .andExpect(jsonPath("$.data.risk_level").value("HIGH"));

        mockMvc.perform(put("/production/safety-environment/events/{eventNo}/status", eventNo)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 953102L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "CLOSED",
                                  "responsible_owner": "安环员",
                                  "description": "现场已清理并人工关闭整改"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.event_no").value(eventNo))
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.closed_at").isNotEmpty())
                .andExpect(jsonPath("$.data.description").value("现场已清理并人工关闭整改"));

        mockMvc.perform(get("/production/safety-environment/summary")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", 953103L)
                        .param("event_no_prefix", eventNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_event_count").value(1))
                .andExpect(jsonPath("$.data.hazard_rectification_count").value(1))
                .andExpect(jsonPath("$.data.pending_count").value(0))
                .andExpect(jsonPath("$.data.closed_count").value(1))
                .andExpect(jsonPath("$.data.high_risk_count").value(1));
    }

    @Test
    void doctorCannotCreateOrUpdateProductionSafetyEvent() throws Exception {
        mockMvc.perform(post("/production/safety-environment/events")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", 953201L)
                        .header("X-Bootstrap-Clinic-Id", 12L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "event_no": "SAFE95_DOC_BLOCK",
                                  "event_type": "SAFETY_INSPECTION",
                                  "description": "医生端不可写内部安环事件"
                                }
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/production/safety-environment/events/{eventNo}/status", "SAFE95_DOC_BLOCK")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", 953201L)
                        .header("X-Bootstrap-Clinic-Id", 12L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "CLOSED",
                                  "description": "医生端不可关闭内部整改"
                                }
                                """))
                .andExpect(status().isForbidden());
    }
}
