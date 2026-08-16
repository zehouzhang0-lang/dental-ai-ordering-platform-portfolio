package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ProductionWorkbenchDepartmentSummaryResponse(
        @JsonProperty("generated_at") LocalDateTime generatedAt,
        LocalDate today,
        @JsonProperty("last_month") String lastMonth,
        List<DepartmentRow> departments,
        @JsonProperty("trend_metrics") List<TrendMetric> trendMetrics,
        List<DepartmentTrend> trends) {

    public record DepartmentRow(
            @JsonProperty("department_key") String departmentKey,
            @JsonProperty("department_name") String departmentName,
            @JsonProperty("department_subtitle") String departmentSubtitle,
            @JsonProperty("display_order") int displayOrder,
            @JsonProperty("today_task_count") long todayTaskCount,
            @JsonProperty("last_month_daily_avg_task_count") long lastMonthDailyAvgTaskCount,
            @JsonProperty("today_completion_rate") double todayCompletionRate,
            @JsonProperty("today_rework_rate") double todayReworkRate,
            @JsonProperty("last_month_rework_rate") double lastMonthReworkRate,
            @JsonProperty("today_shipping_rate") double todayShippingRate,
            @JsonProperty("last_month_shipping_rate") double lastMonthShippingRate,
            String status,
            @JsonProperty("status_label") String statusLabel) {
    }

    public record TrendMetric(String key, String label) {
    }

    public record DepartmentTrend(
            @JsonProperty("department_key") String departmentKey,
            @JsonProperty("department_name") String departmentName,
            List<TrendPoint> points) {
    }

    public record TrendPoint(
            LocalDate date,
            @JsonProperty("completion_rate") double completionRate,
            @JsonProperty("rework_rate") double reworkRate,
            @JsonProperty("shipping_rate") double shippingRate) {
    }
}
