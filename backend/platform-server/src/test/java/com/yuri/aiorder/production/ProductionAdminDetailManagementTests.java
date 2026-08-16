package com.yuri.aiorder.production;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ProductionAdminDetailManagementTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void adminCanReadAndOperateSupportDetails() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        long orderId = createOrder("ADMIN_DETAIL_" + suffix);

        String equipmentCode = "EQ_DETAIL_" + suffix;
        mockMvc.perform(post("/production/equipment")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", 9601L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "equipment_code": "%s",
                                  "equipment_name": "验收切削机",
                                  "equipment_type": "MILLING_MACHINE",
                                  "department_name": "切削组",
                                  "status": "RUNNING",
                                  "utilization_rate": 82.5
                                }
                                """.formatted(equipmentCode)))
                .andExpect(status().isOk());

        String approvalBody = mockMvc.perform(post("/production/equipment/{equipmentCode}/events", equipmentCode)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", 9601L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "event_type": "REPAIR_REQUEST",
                                  "status": "PENDING",
                                  "downtime_minutes": 60,
                                  "description": "申请停机维修"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requested_by_user_id").value(9601L))
                .andReturn().getResponse().getContentAsString();
        long approvalId = ((Number) com.jayway.jsonpath.JsonPath.read(approvalBody, "$.data.event_id")).longValue();

        mockMvc.perform(get("/production/equipment")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 8001L)
                        .param("keyword", equipmentCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].equipment_code").value(equipmentCode));
        mockMvc.perform(get("/production/equipment/{equipmentCode}", equipmentCode)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 8001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events[0].event_type").value("REPAIR_REQUEST"));
        mockMvc.perform(put("/production/equipment/approvals/{eventId}", approvalId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 8001L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\",\"decision_note\":\"同意维修\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.approved_by_user_id").value(8001L));

        String exceptionNo = "MAT_DETAIL_" + suffix;
        mockMvc.perform(post("/production/material-exceptions")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", 9601L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"exception_no":"%s","material_code":"ZIR_DETAIL","material_name":"验收瓷块",
                                 "order_id":%d,"exception_type":"SHORTAGE","status":"PENDING","loss_quantity":1.5}
                                """.formatted(exceptionNo, orderId)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/production/material-exceptions/{exceptionNo}", exceptionNo)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 8001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").value(orderId));

        String eventNo = "SAFE_DETAIL_" + suffix;
        mockMvc.perform(post("/production/safety-environment/events")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", 9601L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event_no":"%s","event_type":"SAFETY_INSPECTION","status":"PENDING",
                                 "department_name":"切削组","risk_level":"HIGH","description":"安全点检"}
                                """.formatted(eventNo)))
                .andExpect(status().isOk());
        insertSafetyRule("RULE_DETAIL_" + suffix);
        mockMvc.perform(get("/production/safety-environment/rules")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 8001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.rule_code == 'RULE_DETAIL_%s')]".formatted(suffix)).exists());

        String costNo = "COST_DETAIL_" + suffix;
        mockMvc.perform(post("/production/cost-management/records")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", 9601L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cost_no":"%s","order_id":%d,"cost_type":"MATERIAL","amount":88.50,
                                 "status":"WARNING","department_name":"切削组","description":"材料成本待确认"}
                                """.formatted(costNo, orderId)))
                .andExpect(status().isOk());
        mockMvc.perform(put("/production/cost-management/records/{costNo}/status", costNo)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 8001L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.confirmed_at").isNotEmpty());

        String batchNo = "OUT_DETAIL_" + suffix;
        insertOutsourcing(batchNo, orderId);
        mockMvc.perform(get("/production/outsourcing/{batchNo}", batchNo)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 8001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").value(orderId))
                .andExpect(jsonPath("$.data.is_overdue").value(true));
    }

    private long createOrder(String orderNo) {
        jdbcClient.sql("""
                        INSERT INTO orders (order_no, clinic_id, product_type, form_data, internal_status, external_status)
                        SELECT :orderNo, clinic_id, 'REGULAR_CROWN', JSON_OBJECT('item_count', 1),
                               'PRODUCING', 'IN_PRODUCTION'
                        FROM clinic ORDER BY clinic_id LIMIT 1
                        """)
                .param("orderNo", orderNo)
                .update();
        return jdbcClient.sql("SELECT order_id FROM orders WHERE order_no = :orderNo")
                .param("orderNo", orderNo)
                .query(Long.class)
                .single();
    }

    private void insertSafetyRule(String ruleCode) {
        jdbcClient.sql("""
                        INSERT INTO production_safety_rule
                            (rule_code, rule_name, check_type, department_name, cycle_type,
                             cycle_interval, responsible_owner, next_due_at, status)
                        VALUES (:ruleCode, '每日安全点检', 'SAFETY', '切削组', 'DAILY', 1,
                                '测试负责人', :nextDueAt, 'ACTIVE')
                        """)
                .param("ruleCode", ruleCode)
                .param("nextDueAt", LocalDateTime.now().plusDays(1))
                .update();
    }

    private void insertOutsourcing(String batchNo, long orderId) {
        jdbcClient.sql("""
                        INSERT INTO production_outsourcing_batch
                            (batch_no, order_id, item_name, supplier_name, quantity, status,
                             sent_at, expected_return_at, abnormal_note)
                        VALUES (:batchNo, :orderId, '钛基台', '验收供应商', 1, 'DELAYED',
                                :sentAt, :expectedReturnAt, '返回延迟')
                        """)
                .param("batchNo", batchNo)
                .param("orderId", orderId)
                .param("sentAt", LocalDateTime.now().minusDays(3))
                .param("expectedReturnAt", LocalDateTime.now().minusDays(1))
                .update();
    }
}
