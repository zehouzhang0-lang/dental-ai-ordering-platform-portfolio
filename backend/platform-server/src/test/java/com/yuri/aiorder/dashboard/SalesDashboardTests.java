package com.yuri.aiorder.dashboard;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class SalesDashboardTests {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    private long clinicId;
    private long dashboardUserId;
    private long baselineInboundCurrent;
    private long baselineInboundPrevious;
    private long baselineOutboundCurrent;
    private long baselineOutboundPrevious;
    private long baselineMonthInboundCurrent;
    private long baselineMonthInboundPrevious;
    private long baselineMonthOutboundCurrent;
    private long baselineMonthOutboundPrevious;
    private long baselineComparisonInboundCurrent;
    private long baselineComparisonInboundPrevious;
    private long baselineComparisonOutboundCurrent;
    private long baselineComparisonOutboundPrevious;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        dashboardUserId = 920_000L + Long.parseLong(suffix.substring(0, 6), 16) % 70_000L;
        clinicId = insertClinic("销售趋势客户-" + suffix.substring(0, 8));
        captureDashboardBaseline();

        long currentInboundOnly = insertOrder("SALES-CUR-IN-" + suffix.substring(0, 8), 120_000, false, false);
        long currentShipped = insertOrder("SALES-CUR-OUT-" + suffix.substring(8, 16), 80_000, true, false);
        long previousInboundOnly = insertOrder("SALES-PREV-IN-" + suffix.substring(16, 24), 100_000, false, true);
        long previousShipped = insertOrder("SALES-PREV-OUT-" + suffix.substring(24, 32), 50_000, true, true);

        insertApprovalHistory(currentInboundOnly, false);
        insertApprovalHistory(currentShipped, false);
        insertApprovalHistory(previousInboundOnly, true);
        insertApprovalHistory(previousShipped, true);

        long previousMonthInboundOnly = insertPreviousMonthOrder(
                "SALES-MONTH-IN-" + suffix.substring(0, 8), 70_000, false);
        long previousMonthShipped = insertPreviousMonthOrder(
                "SALES-MONTH-OUT-" + suffix.substring(8, 16), 40_000, true);
        insertPreviousMonthApprovalHistory(previousMonthInboundOnly);
        insertPreviousMonthApprovalHistory(previousMonthShipped);
    }

    @AfterEach
    void tearDown() {
        jdbcClient.sql("""
                        DELETE h FROM order_status_history h
                        JOIN orders o ON o.order_id = h.order_id
                        WHERE o.cs_user_id = :dashboardUserId
                        """)
                .param("dashboardUserId", dashboardUserId)
                .update();
        jdbcClient.sql("""
                        DELETE l FROM order_logistics l
                        JOIN orders o ON o.order_id = l.order_id
                        WHERE o.cs_user_id = :dashboardUserId
                        """)
                .param("dashboardUserId", dashboardUserId)
                .update();
        jdbcClient.sql("""
                        DELETE b FROM order_bill b
                        JOIN orders o ON o.order_id = b.order_id
                        WHERE o.cs_user_id = :dashboardUserId
                        """)
                .param("dashboardUserId", dashboardUserId)
                .update();
        jdbcClient.sql("DELETE FROM orders WHERE cs_user_id = :dashboardUserId")
                .param("dashboardUserId", dashboardUserId)
                .update();
        jdbcClient.sql("DELETE FROM clinic WHERE clinic_id = :clinicId")
                .param("clinicId", clinicId)
                .update();
    }

    @Test
    void csCanReadInboundAndOutboundSalesWithPreviousYearComparison() throws Exception {
        int currentMonthIndex = LocalDate.now().getMonthValue() - 1;
        int comparisonDayCount = Math.min(
                LocalDate.now().getDayOfMonth(),
                LocalDate.now().minusMonths(1).lengthOfMonth());
        boolean todayIsIncludedInMatchedDayComparison =
                LocalDate.now().getDayOfMonth() <= comparisonDayCount;
        long expectedInboundCurrent = baselineInboundCurrent + 200_000 + 110_000;
        long expectedInboundPrevious = baselineInboundPrevious + 150_000;
        long expectedOutboundCurrent = baselineOutboundCurrent + 80_000 + 40_000;
        long expectedOutboundPrevious = baselineOutboundPrevious + 50_000;
        long currentComparisonInboundDelta = todayIsIncludedInMatchedDayComparison ? 200_000 : 0;
        long currentComparisonOutboundDelta = todayIsIncludedInMatchedDayComparison ? 80_000 : 0;

        mockMvc.perform(get("/dashboards/sales")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", dashboardUserId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currency").value("CNY"))
                .andExpect(jsonPath("$.data.inbound.current_amount_cents").value(expectedInboundCurrent))
                .andExpect(jsonPath("$.data.inbound.previous_year_amount_cents").value(expectedInboundPrevious))
                .andExpect(jsonPath("$.data.inbound.year_over_year_percent").value(closeTo(
                        yearOverYearPercent(expectedInboundCurrent, expectedInboundPrevious), 0.01)))
                .andExpect(jsonPath("$.data.outbound.current_amount_cents").value(expectedOutboundCurrent))
                .andExpect(jsonPath("$.data.outbound.previous_year_amount_cents").value(expectedOutboundPrevious))
                .andExpect(jsonPath("$.data.outbound.year_over_year_percent").value(closeTo(
                        yearOverYearPercent(expectedOutboundCurrent, expectedOutboundPrevious), 0.01)))
                .andExpect(jsonPath("$.data.monthly_trend", hasSize(12)))
                .andExpect(jsonPath("$.data.monthly_trend[" + currentMonthIndex + "].inbound_amount_cents")
                        .value(baselineMonthInboundCurrent + 200_000))
                .andExpect(jsonPath("$.data.monthly_trend[" + currentMonthIndex + "].outbound_amount_cents")
                        .value(baselineMonthOutboundCurrent + 80_000))
                .andExpect(jsonPath("$.data.monthly_trend[" + currentMonthIndex + "].previous_year_inbound_amount_cents")
                        .value(baselineMonthInboundPrevious + 150_000))
                .andExpect(jsonPath("$.data.monthly_trend[" + currentMonthIndex + "].previous_year_outbound_amount_cents")
                        .value(baselineMonthOutboundPrevious + 50_000))
                .andExpect(jsonPath("$.data.month_comparison.inbound.current_amount_cents")
                        .value(baselineComparisonInboundCurrent + currentComparisonInboundDelta))
                .andExpect(jsonPath("$.data.month_comparison.inbound.previous_month_amount_cents")
                        .value(baselineComparisonInboundPrevious + 110_000))
                .andExpect(jsonPath("$.data.month_comparison.outbound.current_amount_cents")
                        .value(baselineComparisonOutboundCurrent + currentComparisonOutboundDelta))
                .andExpect(jsonPath("$.data.month_comparison.outbound.previous_month_amount_cents")
                        .value(baselineComparisonOutboundPrevious + 40_000))
                .andExpect(jsonPath("$.data.month_comparison.daily_trend",
                        hasSize(Math.min(LocalDate.now().getDayOfMonth(), LocalDate.now().minusMonths(1).lengthOfMonth()))));
    }

    @Test
    void workerAndDoctorCannotReadSalesAmounts() throws Exception {
        mockMvc.perform(get("/dashboards/sales")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", dashboardUserId))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/dashboards/sales")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", 9701L)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isForbidden());
    }

    private long insertClinic(String clinicName) {
        jdbcClient.sql("INSERT INTO clinic (clinic_name) VALUES (:clinicName)")
                .param("clinicName", clinicName)
                .update();
        return jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    private void captureDashboardBaseline() throws Exception {
        int currentMonthIndex = LocalDate.now().getMonthValue() - 1;
        MvcResult result = mockMvc.perform(get("/dashboards/sales")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", dashboardUserId))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        baselineInboundCurrent = jsonLong(body, "$.data.inbound.current_amount_cents");
        baselineInboundPrevious = jsonLong(body, "$.data.inbound.previous_year_amount_cents");
        baselineOutboundCurrent = jsonLong(body, "$.data.outbound.current_amount_cents");
        baselineOutboundPrevious = jsonLong(body, "$.data.outbound.previous_year_amount_cents");
        String monthPath = "$.data.monthly_trend[" + currentMonthIndex + "]";
        baselineMonthInboundCurrent = jsonLong(body, monthPath + ".inbound_amount_cents");
        baselineMonthInboundPrevious = jsonLong(body, monthPath + ".previous_year_inbound_amount_cents");
        baselineMonthOutboundCurrent = jsonLong(body, monthPath + ".outbound_amount_cents");
        baselineMonthOutboundPrevious = jsonLong(body, monthPath + ".previous_year_outbound_amount_cents");
        baselineComparisonInboundCurrent = jsonLong(body, "$.data.month_comparison.inbound.current_amount_cents");
        baselineComparisonInboundPrevious = jsonLong(body, "$.data.month_comparison.inbound.previous_month_amount_cents");
        baselineComparisonOutboundCurrent = jsonLong(body, "$.data.month_comparison.outbound.current_amount_cents");
        baselineComparisonOutboundPrevious = jsonLong(body, "$.data.month_comparison.outbound.previous_month_amount_cents");
    }

    private long jsonLong(String body, String path) {
        return ((Number) JsonPath.read(body, path)).longValue();
    }

    private double yearOverYearPercent(long current, long previous) {
        return Math.round(((current - previous) * 10_000.0 / previous)) / 100.0;
    }

    private long insertOrder(String orderNo, long amountCents, boolean shipped, boolean previousYear) {
        jdbcClient.sql("""
                        INSERT INTO orders
                            (order_no, clinic_id, doctor_user_id, cs_user_id, product_type, form_data,
                             internal_status, external_status, created_at)
                        VALUES
                            (:orderNo, :clinicId, 9701, :dashboardUserId, 'REGULAR_CROWN',
                             JSON_OBJECT('item_count', 1), :internalStatus, :externalStatus,
                             IF(:previousYear, DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 YEAR), CURRENT_TIMESTAMP(3)))
                        """)
                .param("orderNo", orderNo)
                .param("clinicId", clinicId)
                .param("dashboardUserId", dashboardUserId)
                .param("internalStatus", shipped ? "SHIPPED" : "PENDING_PRODUCTION_REVIEW")
                .param("externalStatus", shipped ? "SHIPPED" : "PENDING_REVIEW")
                .param("previousYear", previousYear)
                .update();
        long orderId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jdbcClient.sql("""
                        INSERT INTO order_bill (order_id, amount_cent, currency, bill_status)
                        VALUES (:orderId, :amountCents, 'CNY', 'UPLOADED')
                        """)
                .param("orderId", orderId)
                .param("amountCents", amountCents)
                .update();
        if (shipped) {
            jdbcClient.sql("""
                            INSERT INTO order_logistics
                                (order_id, logistics_status, shipped_at)
                            VALUES
                                (:orderId, 'SHIPPED',
                                 IF(:previousYear, DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 YEAR), CURRENT_TIMESTAMP(3)))
                            """)
                    .param("orderId", orderId)
                    .param("previousYear", previousYear)
                    .update();
        }
        return orderId;
    }

    private void insertApprovalHistory(long orderId, boolean previousYear) {
        jdbcClient.sql("""
                        INSERT INTO order_status_history
                            (order_id, from_internal_status, to_internal_status,
                             from_external_status, to_external_status, event_type, created_at)
                        VALUES
                            (:orderId, 'PENDING_CS_REVIEW', 'PENDING_PRODUCTION_REVIEW',
                             'PENDING_REVIEW', 'PENDING_REVIEW', 'CS_APPROVED',
                             IF(:previousYear, DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 YEAR), CURRENT_TIMESTAMP(3)))
                        """)
                .param("orderId", orderId)
                .param("previousYear", previousYear)
                .update();
    }

    private long insertPreviousMonthOrder(String orderNo, long amountCents, boolean shipped) {
        jdbcClient.sql("""
                        INSERT INTO orders
                            (order_no, clinic_id, doctor_user_id, cs_user_id, product_type, form_data,
                             internal_status, external_status, created_at)
                        VALUES
                            (:orderNo, :clinicId, 9701, :dashboardUserId, 'REGULAR_CROWN',
                             JSON_OBJECT('item_count', 1), :internalStatus, :externalStatus,
                             DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MONTH))
                        """)
                .param("orderNo", orderNo)
                .param("clinicId", clinicId)
                .param("dashboardUserId", dashboardUserId)
                .param("internalStatus", shipped ? "SHIPPED" : "PENDING_PRODUCTION_REVIEW")
                .param("externalStatus", shipped ? "SHIPPED" : "PENDING_REVIEW")
                .update();
        long orderId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jdbcClient.sql("""
                        INSERT INTO order_bill (order_id, amount_cent, currency, bill_status)
                        VALUES (:orderId, :amountCents, 'CNY', 'UPLOADED')
                        """)
                .param("orderId", orderId)
                .param("amountCents", amountCents)
                .update();
        if (shipped) {
            jdbcClient.sql("""
                            INSERT INTO order_logistics (order_id, logistics_status, shipped_at)
                            VALUES (:orderId, 'SHIPPED', DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MONTH))
                            """)
                    .param("orderId", orderId)
                    .update();
        }
        return orderId;
    }

    private void insertPreviousMonthApprovalHistory(long orderId) {
        jdbcClient.sql("""
                        INSERT INTO order_status_history
                            (order_id, from_internal_status, to_internal_status,
                             from_external_status, to_external_status, event_type, created_at)
                        VALUES
                            (:orderId, 'PENDING_CS_REVIEW', 'PENDING_PRODUCTION_REVIEW',
                             'PENDING_REVIEW', 'PENDING_REVIEW', 'CS_APPROVED',
                             DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MONTH))
                        """)
                .param("orderId", orderId)
                .update();
    }
}
