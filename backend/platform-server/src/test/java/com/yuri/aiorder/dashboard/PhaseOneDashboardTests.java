package com.yuri.aiorder.dashboard;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class PhaseOneDashboardTests {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    private long topClinicId;
    private long otherClinicId;
    private long dashboardUserId;
    private String topClinicName;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        dashboardUserId = 900_000L + Long.parseLong(suffix.substring(0, 6), 16) % 100_000L;
        topClinicName = "趋势客户-" + suffix.substring(0, 8);
        topClinicId = insertClinic(topClinicName);
        otherClinicId = insertClinic("趋势次客户-" + suffix.substring(8, 16));

        for (int index = 0; index < 12; index++) {
            insertOrder("DASH-CUR-A-" + index + "-" + suffix.substring(0, 8),
                    topClinicId, "PENDING_CS_REVIEW", "PENDING_REVIEW", 3, 0);
        }
        insertOrder("DASH-CUR-B-" + suffix.substring(0, 8), topClinicId, "PRODUCING", "PRODUCING", 2, 0);
        insertOrder("DASH-CUR-C-" + suffix.substring(0, 8), otherClinicId, "COMPLETED", "COMPLETED", 1, 0);
        insertOrder("DASH-PREV-" + suffix.substring(0, 8), otherClinicId, "COMPLETED", "COMPLETED", 4, 1);
    }

    @AfterEach
    void tearDown() {
        jdbcClient.sql("DELETE FROM orders WHERE cs_user_id = :dashboardUserId")
                .param("dashboardUserId", dashboardUserId)
                .update();
        jdbcClient.sql("DELETE FROM clinic WHERE clinic_id IN (:topClinicId, :otherClinicId)")
                .param("topClinicId", topClinicId)
                .param("otherClinicId", otherClinicId)
                .update();
    }

    @Test
    void csCanReadMonthlyTrendAndCustomerRanking() throws Exception {
        mockMvc.perform(get("/dashboards/phase-one-ab")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", dashboardUserId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current_month.order_count").value(greaterThanOrEqualTo(14)))
                .andExpect(jsonPath("$.data.current_month.item_count").value(greaterThanOrEqualTo(39)))
                .andExpect(jsonPath("$.data.previous_month.order_count").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.previous_month.item_count").value(greaterThanOrEqualTo(4)))
                .andExpect(jsonPath("$.data.monthly_order_delta").exists())
                .andExpect(jsonPath("$.data.top_customers", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.data.top_customers[0].clinic_id").exists())
                .andExpect(jsonPath("$.data.top_customers[0].clinic_name").exists())
                .andExpect(jsonPath("$.data.top_customers[0].order_count").exists())
                .andExpect(jsonPath("$.data.top_customers[0].item_count").exists())
                // 十大客户带出上月同口径对照：榜首客户上月无订单，次位客户上月有 1 单 4 件。
                .andExpect(jsonPath("$.data.top_customers[0].previous_month_order_count").value(0))
                .andExpect(jsonPath("$.data.top_customers[0].order_count_delta").value(13))
                .andExpect(jsonPath("$.data.top_customers[1].order_count").value(1))
                .andExpect(jsonPath("$.data.top_customers[1].previous_month_order_count").value(1))
                .andExpect(jsonPath("$.data.top_customers[1].previous_month_item_count").value(4))
                .andExpect(jsonPath("$.data.production_exception_count").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.pending_question_count").value(greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.data.previous_month_shipping_rate").exists())
                .andExpect(jsonPath("$.data.previous_week_shipping_rate").exists())
                .andExpect(jsonPath("$.data.source_note").value("本地月度趋势接口 / 客户排名第一段"))
                .andExpect(content().string(containsString(topClinicName)));
    }

    @Test
    void doctorCannotReadPhaseOneDashboard() throws Exception {
        mockMvc.perform(get("/dashboards/phase-one-ab")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", 9701L)
                        .header("X-Bootstrap-Clinic-Id", topClinicId))
                .andExpect(status().isForbidden());
    }

    private long insertClinic(String clinicName) {
        jdbcClient.sql("INSERT INTO clinic (clinic_name) VALUES (:clinicName)")
                .param("clinicName", clinicName)
                .update();
        return jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    private void insertOrder(
            String orderNo,
            long clinicId,
            String internalStatus,
            String externalStatus,
            int itemCount,
            int monthsAgo) {
        jdbcClient.sql("""
                        INSERT INTO orders
                            (order_no, clinic_id, doctor_user_id, cs_user_id, product_type, form_data,
                             internal_status, external_status, created_at)
                        VALUES
                            (:orderNo, :clinicId, 9701, :dashboardUserId, 'REGULAR_CROWN',
                             JSON_OBJECT('item_count', :itemCount),
                             :internalStatus, :externalStatus,
                             DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL :monthsAgo MONTH))
                        """)
                .param("orderNo", orderNo)
                .param("clinicId", clinicId)
                .param("itemCount", itemCount)
                .param("internalStatus", internalStatus)
                .param("externalStatus", externalStatus)
                .param("dashboardUserId", dashboardUserId)
                .param("monthsAgo", monthsAgo)
                .update();
    }
}
