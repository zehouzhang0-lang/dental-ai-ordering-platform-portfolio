package com.yuri.aiorder.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;

public record PhaseOneDashboardResponse(
        @JsonProperty("current_month") MonthSummary currentMonth,
        @JsonProperty("previous_month") MonthSummary previousMonth,
        @JsonProperty("monthly_order_delta") long monthlyOrderDelta,
        @JsonProperty("monthly_item_delta") long monthlyItemDelta,
        @JsonProperty("top_customers") List<CustomerRanking> topCustomers,
        @JsonProperty("production_exception_count") long productionExceptionCount,
        @JsonProperty("pending_question_count") long pendingQuestionCount,
        @JsonProperty("shipping_rate") int shippingRate,
        @JsonProperty("previous_month_shipping_rate") int previousMonthShippingRate,
        @JsonProperty("previous_week_shipping_rate") int previousWeekShippingRate,
        @JsonProperty("completion_rate") int completionRate,
        @JsonProperty("source_note") String sourceNote,
        @JsonProperty("generated_at") LocalDateTime generatedAt) {

    public record MonthSummary(
            @JsonProperty("month") String month,
            @JsonProperty("order_count") long orderCount,
            @JsonProperty("item_count") long itemCount) {
    }

    public record CustomerRanking(
            @JsonProperty("clinic_id") long clinicId,
            @JsonProperty("clinic_name") String clinicName,
            @JsonProperty("order_count") long orderCount,
            @JsonProperty("item_count") long itemCount,
            @JsonProperty("previous_month_order_count") long previousMonthOrderCount,
            @JsonProperty("previous_month_item_count") long previousMonthItemCount,
            @JsonProperty("order_count_delta") long orderCountDelta,
            @JsonProperty("item_count_delta") long itemCountDelta) {
    }
}
