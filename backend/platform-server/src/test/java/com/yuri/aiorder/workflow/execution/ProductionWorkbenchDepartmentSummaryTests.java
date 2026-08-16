package com.yuri.aiorder.workflow.execution;

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
class ProductionWorkbenchDepartmentSummaryTests {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void productionWorkbenchDepartmentSummaryReturnsDepartmentRowsAndTrends() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String orderNoPrefix = "WB" + suffix + "_";
        long clinicId = createClinic("生产工作台部门测试-" + suffix);
        long workerUserId = 997_000_000L + Long.parseLong(suffix.substring(0, 6), 16);

        for (int index = 0; index < 4; index++) {
            createNodeFact(orderNoPrefix + "TODAY_DONE_" + index, clinicId, workerUserId,
                    "CAD", "CAD设计", "COMPLETED", "TODAY", true, index < 3, index == 0);
        }
        createNodeFact(orderNoPrefix + "TODAY_READY", clinicId, workerUserId,
                "CAD", "CAD排版", "READY", null, false, false, false);

        for (int index = 0; index < 6; index++) {
            createNodeFact(orderNoPrefix + "PREV_DONE_" + index, clinicId, workerUserId,
                    "CAD", "CAD设计", "COMPLETED", "LAST_MONTH", true, index < 5, index == 0);
        }

        mockMvc.perform(get("/production/workbench/department-summary")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", workerUserId)
                        .param("order_no_prefix", orderNoPrefix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.departments.length()").value(13))
                .andExpect(jsonPath("$.data.departments[0].department_key").value("DATA_REVIEW"))
                .andExpect(jsonPath("$.data.departments[0].department_name").value("数据处理"))
                .andExpect(jsonPath("$.data.departments[1].department_key").value("CAD"))
                .andExpect(jsonPath("$.data.departments[1].department_name").value("CAD"))
                .andExpect(jsonPath("$.data.departments[1].department_subtitle").value("Design"))
                .andExpect(jsonPath("$.data.departments[1].today_task_count").value(5))
                .andExpect(jsonPath("$.data.departments[1].last_month_daily_avg_task_count").value(6))
                .andExpect(jsonPath("$.data.departments[1].today_completion_rate").value(80.0))
                .andExpect(jsonPath("$.data.departments[1].today_rework_rate").value(25.0))
                .andExpect(jsonPath("$.data.departments[1].last_month_rework_rate").value(16.7))
                .andExpect(jsonPath("$.data.departments[1].today_shipping_rate").value(60.0))
                .andExpect(jsonPath("$.data.departments[1].last_month_shipping_rate").value(83.3))
                .andExpect(jsonPath("$.data.departments[1].status").value("RISK"))
                .andExpect(jsonPath("$.data.departments[1].status_label").value("风险"))
                .andExpect(jsonPath("$.data.trend_metrics.length()").value(3))
                .andExpect(jsonPath("$.data.trend_metrics[0].key").value("completion_rate"))
                .andExpect(jsonPath("$.data.trends[0].department_key").value("ALL"))
                .andExpect(jsonPath("$.data.trends[0].points.length()").value(7));
    }

    @Test
    void doctorCannotReadProductionWorkbenchDepartmentSummary() throws Exception {
        mockMvc.perform(get("/production/workbench/department-summary")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", 997100001L)
                        .header("X-Bootstrap-Clinic-Id", 1L))
                .andExpect(status().isForbidden());
    }

    private long createClinic(String clinicName) {
        jdbcClient.sql("INSERT INTO clinic (clinic_name) VALUES (:clinicName)")
                .param("clinicName", clinicName)
                .update();
        return jdbcClient.sql("SELECT clinic_id FROM clinic WHERE clinic_name = :clinicName")
                .param("clinicName", clinicName)
                .query(Long.class)
                .single();
    }

    private void createNodeFact(
            String orderNo,
            long clinicId,
            long workerUserId,
            String stageName,
            String processName,
            String nodeStatus,
            String completedPeriod,
            boolean hasCompletedWorkLog,
            boolean nodeCompleted,
            boolean hasRework) {
        long chainId = createChain(orderNo, stageName, processName);
        long orderId = createOrder(orderNo, clinicId);
        long instanceId = createInstance(orderId, chainId);
        long sourceNodeId = jdbcClient.sql("SELECT node_id FROM workflow_node WHERE chain_id = :chainId")
                .param("chainId", chainId)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO order_process_node
                            (instance_id, source_node_id, node_code, process_name, stage_name, step_order,
                             standard_duration, node_category, node_status, assigned_user_id, completed_at)
                        VALUES
                            (:instanceId, :sourceNodeId, :nodeCode, :processName, :stageName, 10,
                             600, 'PRODUCTION', :nodeStatus, :workerUserId, %s)
                        """.formatted(timestampExpression(completedPeriod, nodeCompleted)))
                .param("instanceId", instanceId)
                .param("sourceNodeId", sourceNodeId)
                .param("nodeCode", "NODE_" + orderNo)
                .param("processName", processName)
                .param("stageName", stageName)
                .param("nodeStatus", nodeStatus)
                .param("workerUserId", workerUserId)
                .update();
        long nodeId = jdbcClient.sql("SELECT node_instance_id FROM order_process_node WHERE node_code = :nodeCode")
                .param("nodeCode", "NODE_" + orderNo)
                .query(Long.class)
                .single();
        if (hasCompletedWorkLog) {
            jdbcClient.sql("""
                            INSERT INTO work_log
                                (order_id, node_instance_id, worker_user_id, started_at, finished_at,
                                 effective_duration_seconds, status)
                            VALUES
                                (:orderId, :nodeId, :workerUserId,
                                 DATE_SUB(%s, INTERVAL 8 MINUTE), %s, 480, 'COMPLETED')
                            """.formatted(timestampExpression(completedPeriod, true), timestampExpression(completedPeriod, true)))
                    .param("orderId", orderId)
                    .param("nodeId", nodeId)
                    .param("workerUserId", workerUserId)
                    .update();
        }
        if (hasRework) {
            jdbcClient.sql("""
                            INSERT INTO check_record
                                (order_id, node_instance_id, check_type, result, checker_user_id, created_at)
                            VALUES
                                (:orderId, :nodeId, 'OUT', 'FAIL', :workerUserId, %s)
                            """.formatted(timestampExpression(completedPeriod, true)))
                    .param("orderId", orderId)
                    .param("nodeId", nodeId)
                    .param("workerUserId", workerUserId)
                    .update();
            long checkId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
            jdbcClient.sql("""
                            INSERT INTO rework_record
                                (order_id, source_check_id, from_node_instance_id, target_node_instance_id,
                                 responsibility_type, status, created_at)
                            VALUES
                                (:orderId, :checkId, :nodeId, :nodeId, 'WORKER', 'PENDING', %s)
                            """.formatted(timestampExpression(completedPeriod, true)))
                    .param("orderId", orderId)
                    .param("checkId", checkId)
                    .param("nodeId", nodeId)
                    .update();
        }
    }

    private long createChain(String suffix, String stageName, String processName) {
        String chainCode = "workbench_" + suffix;
        jdbcClient.sql("""
                        INSERT INTO workflow_chain
                            (chain_code, chain_name, product_type, version, intake_branch, status)
                        VALUES
                            (:chainCode, :chainName, 'WORKBENCH_TEST', 1, 'BOTH', 1)
                        """)
                .param("chainCode", chainCode)
                .param("chainName", "工作台部门测试-" + suffix)
                .update();
        long chainId = jdbcClient.sql("SELECT chain_id FROM workflow_chain WHERE chain_code = :chainCode")
                .param("chainCode", chainCode)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO workflow_node
                            (chain_id, node_code, process_name, stage_name, step_order, is_optional,
                             standard_duration, node_category, need_in_check, need_out_check)
                        VALUES
                            (:chainId, :nodeCode, :processName, :stageName, 10, 0,
                             600, 'PRODUCTION', 1, 1)
                        """)
                .param("chainId", chainId)
                .param("nodeCode", "SRC_" + suffix)
                .param("processName", processName)
                .param("stageName", stageName)
                .update();
        return chainId;
    }

    private long createOrder(String orderNo, long clinicId) {
        jdbcClient.sql("""
                        INSERT INTO orders
                            (order_no, clinic_id, doctor_user_id, cs_user_id, product_type,
                             internal_status, external_status)
                        VALUES
                            (:orderNo, :clinicId, 997200001, 997200002, 'WORKBENCH_TEST',
                             'PRODUCING', 'PRODUCING')
                        """)
                .param("orderNo", orderNo)
                .param("clinicId", clinicId)
                .update();
        return jdbcClient.sql("SELECT order_id FROM orders WHERE order_no = :orderNo")
                .param("orderNo", orderNo)
                .query(Long.class)
                .single();
    }

    private long createInstance(long orderId, long chainId) {
        jdbcClient.sql("""
                        INSERT INTO order_process_instance
                            (order_id, chain_id, chain_version, instance_status)
                        VALUES
                            (:orderId, :chainId, 1, 'IN_PROGRESS')
                        """)
                .param("orderId", orderId)
                .param("chainId", chainId)
                .update();
        return jdbcClient.sql("SELECT instance_id FROM order_process_instance WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    private String timestampExpression(String period, boolean present) {
        if (!present || period == null) {
            return "NULL";
        }
        if ("LAST_MONTH".equals(period)) {
            return "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MONTH)";
        }
        return "CURRENT_TIMESTAMP(3)";
    }
}
