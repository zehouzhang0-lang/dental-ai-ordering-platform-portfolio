package com.yuri.aiorder.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

public record SalesDashboardResponse(
        @JsonProperty("current_year") int currentYear,
        @JsonProperty("through_date") LocalDate throughDate,
        @JsonProperty("currency") String currency,
        @JsonProperty("inbound") SalesComparison inbound,
        @JsonProperty("outbound") SalesComparison outbound,
        @JsonProperty("monthly_trend") List<MonthlySalesTrend> monthlyTrend,
        @JsonProperty("month_comparison") MonthComparison monthComparison,
        @JsonProperty("source_note") String sourceNote,
        @JsonProperty("generated_at") LocalDateTime generatedAt) {

    public record SalesComparison(
            @JsonProperty("current_amount_cents") long currentAmountCents,
            @JsonProperty("previous_year_amount_cents") long previousYearAmountCents,
            @JsonProperty("year_over_year_percent") Double yearOverYearPercent,
            @JsonProperty("current_order_count") long currentOrderCount,
            @JsonProperty("previous_year_order_count") long previousYearOrderCount,
            @JsonProperty("current_amount_order_count") long currentAmountOrderCount,
            @JsonProperty("previous_year_amount_order_count") long previousYearAmountOrderCount) {
    }

    public record MonthlySalesTrend(
            @JsonProperty("month") int month,
            @JsonProperty("inbound_amount_cents") long inboundAmountCents,
            @JsonProperty("outbound_amount_cents") long outboundAmountCents,
            @JsonProperty("previous_year_inbound_amount_cents") long previousYearInboundAmountCents,
            @JsonProperty("previous_year_outbound_amount_cents") long previousYearOutboundAmountCents) {
    }

    public record MonthComparison(
            @JsonProperty("current_month") YearMonth currentMonth,
            @JsonProperty("previous_month") YearMonth previousMonth,
            @JsonProperty("through_date") LocalDate throughDate,
            @JsonProperty("comparison_day_count") int comparisonDayCount,
            @JsonProperty("inbound") MonthAmountComparison inbound,
            @JsonProperty("outbound") MonthAmountComparison outbound,
            @JsonProperty("daily_trend") List<DailyMonthSalesTrend> dailyTrend) {
    }

    public record MonthAmountComparison(
            @JsonProperty("current_amount_cents") long currentAmountCents,
            @JsonProperty("previous_month_amount_cents") long previousMonthAmountCents,
            @JsonProperty("month_over_month_percent") Double monthOverMonthPercent,
            @JsonProperty("current_order_count") long currentOrderCount,
            @JsonProperty("previous_month_order_count") long previousMonthOrderCount,
            @JsonProperty("current_amount_order_count") long currentAmountOrderCount,
            @JsonProperty("previous_month_amount_order_count") long previousMonthAmountOrderCount) {
    }

    public record DailyMonthSalesTrend(
            int day,
            @JsonProperty("current_inbound_amount_cents") long currentInboundAmountCents,
            @JsonProperty("previous_month_inbound_amount_cents") long previousMonthInboundAmountCents,
            @JsonProperty("current_outbound_amount_cents") long currentOutboundAmountCents,
            @JsonProperty("previous_month_outbound_amount_cents") long previousMonthOutboundAmountCents) {
    }
}
