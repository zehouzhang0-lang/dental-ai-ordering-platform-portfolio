package com.yuri.aiorder.production;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ProductionMaterialExceptionSummaryTests {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void productionMaterialExceptionSummaryAggregatesTypeStatusAndResponsibility() throws Exception {
        String prefix = "MAT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        createMaterialException(prefix + "_SHORT", "SHORTAGE", "PENDING", "采购部", 3.5);
        createMaterialException(prefix + "_WRONG", "WRONG_MATERIAL", "IN_PROGRESS", "仓储组", 1.0);
        createMaterialException(prefix + "_BATCH", "BATCH_ABNORMAL", "CLOSED", "供应商", 0.0);
        createMaterialException(prefix + "_LOSS", "MATERIAL_LOSS", "PENDING", null, 2.25);

        mockMvc.perform(get("/production/material-exceptions/summary")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", 990200001L)
                        .param("exception_no_prefix", prefix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exception_no_prefix").value(prefix))
                .andExpect(jsonPath("$.data.total_exception_count").value(4))
                .andExpect(jsonPath("$.data.current_month_count").value(4))
                .andExpect(jsonPath("$.data.previous_month_count").value(0))
                .andExpect(jsonPath("$.data.shortage_count").value(1))
                .andExpect(jsonPath("$.data.wrong_material_count").value(1))
                .andExpect(jsonPath("$.data.batch_abnormal_count").value(1))
                .andExpect(jsonPath("$.data.material_loss_count").value(1))
                .andExpect(jsonPath("$.data.pending_count").value(2))
                .andExpect(jsonPath("$.data.in_progress_count").value(1))
                .andExpect(jsonPath("$.data.closed_count").value(1))
                .andExpect(jsonPath("$.data.responsibility_assigned_count").value(3))
                .andExpect(jsonPath("$.data.total_loss_quantity").value(6.75));
    }

    @Test
    void doctorCannotReadProductionMaterialExceptionSummary() throws Exception {
        mockMvc.perform(get("/production/material-exceptions/summary")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", 990100001L))
                .andExpect(status().isForbidden());
    }

    private void createMaterialException(
            String exceptionNo, String exceptionType, String status, String responsibilityOwner, double lossQuantity) {
        jdbcClient.sql("""
                        INSERT INTO production_material_exception
                            (exception_no, material_code, material_name, exception_type, status,
                             responsibility_owner, loss_quantity, description)
                        VALUES
                            (:exceptionNo, :materialCode, '氧化锆瓷块', :exceptionType, :status,
                             :responsibilityOwner, :lossQuantity, '物料异常汇总测试')
                        """)
                .param("exceptionNo", exceptionNo)
                .param("materialCode", exceptionNo.replace("MAT_", "MC_"))
                .param("exceptionType", exceptionType)
                .param("status", status)
                .param("responsibilityOwner", responsibilityOwner)
                .param("lossQuantity", BigDecimal.valueOf(lossQuantity))
                .update();
    }
}
