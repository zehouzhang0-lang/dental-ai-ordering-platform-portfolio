package com.yuri.aiorder.production;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ProductionSafetyEnvironmentSummaryTests {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void productionSafetyEnvironmentSummaryAggregatesTypeStatusRiskAndOverdue() throws Exception {
        String prefix = "SAFE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        createSafetyEvent(
                prefix + "_INSPECT",
                "SAFETY_INSPECTION",
                "PENDING",
                "生产一组",
                "NORMAL",
                LocalDateTime.now().plusDays(1));
        createSafetyEvent(
                prefix + "_HAZARD",
                "HAZARD_RECTIFICATION",
                "IN_PROGRESS",
                "设备组",
                "HIGH",
                LocalDateTime.now().minusDays(1));
        createSafetyEvent(
                prefix + "_ENV",
                "ENVIRONMENT_RECORD",
                "CLOSED",
                "安环组",
                "NORMAL",
                LocalDateTime.now().minusDays(2));
        createSafetyEvent(
                prefix + "_PPE",
                "PPE_DEVICE_REMINDER",
                "PENDING",
                "生产二组",
                "CRITICAL",
                LocalDateTime.now().minusDays(1));

        mockMvc.perform(get("/production/safety-environment/summary")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", 990200001L)
                        .param("event_no_prefix", prefix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.event_no_prefix").value(prefix))
                .andExpect(jsonPath("$.data.total_event_count").value(4))
                .andExpect(jsonPath("$.data.safety_inspection_count").value(1))
                .andExpect(jsonPath("$.data.hazard_rectification_count").value(1))
                .andExpect(jsonPath("$.data.environment_record_count").value(1))
                .andExpect(jsonPath("$.data.ppe_device_reminder_count").value(1))
                .andExpect(jsonPath("$.data.pending_count").value(2))
                .andExpect(jsonPath("$.data.in_progress_count").value(1))
                .andExpect(jsonPath("$.data.closed_count").value(1))
                .andExpect(jsonPath("$.data.overdue_count").value(2))
                .andExpect(jsonPath("$.data.high_risk_count").value(2));
    }

    @Test
    void doctorCannotReadProductionSafetyEnvironmentSummary() throws Exception {
        mockMvc.perform(get("/production/safety-environment/summary")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", 990100001L))
                .andExpect(status().isForbidden());
    }

    private void createSafetyEvent(
            String eventNo,
            String eventType,
            String status,
            String departmentName,
            String riskLevel,
            LocalDateTime dueAt) {
        jdbcClient.sql("""
                        INSERT INTO production_safety_event
                            (event_no, event_type, status, department_name, responsible_owner,
                             equipment_code, risk_level, due_at, description)
                        VALUES
                            (:eventNo, :eventType, :status, :departmentName, '安环测试负责人',
                             :equipmentCode, :riskLevel, :dueAt, '安环汇总测试')
                        """)
                .param("eventNo", eventNo)
                .param("eventType", eventType)
                .param("status", status)
                .param("departmentName", departmentName)
                .param("equipmentCode", eventNo.replace("SAFE_", "EQ_"))
                .param("riskLevel", riskLevel)
                .param("dueAt", dueAt)
                .update();
    }
}
