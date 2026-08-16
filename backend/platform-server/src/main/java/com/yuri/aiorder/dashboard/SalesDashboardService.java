package com.yuri.aiorder.dashboard;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.AccessControlService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class SalesDashboardService {

    private static final String CURRENCY = "CNY";
    private static final String SOURCE_NOTE = "接单按客服审核通过时间（历史缺失时回退订单创建时间），出货按实际发货时间；金额取人民币账单金额";
    private static final String APPROVAL_JOIN = """
            LEFT JOIN (
                SELECT order_id, MIN(created_at) AS approved_at
                FROM order_status_history
                WHERE to_internal_status = 'PENDING_PRODUCTION_REVIEW'
                GROUP BY order_id
            ) approval ON approval.order_id = o.order_id
            """;
    private static final String INBOUND_AT = "COALESCE(approval.approved_at, o.created_at)";
    private static final String OUTBOUND_AT = "l.shipped_at";

    private final JdbcClient jdbcClient;
    private final AccessControlService accessControlService;

    public SalesDashboardService(JdbcClient jdbcClient, AccessControlService accessControlService) {
        this.jdbcClient = jdbcClient;
        this.accessControlService = accessControlService;
    }

    public SalesDashboardResponse getSalesDashboard(BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, "dashboard:read-sales", "sales dashboard requires dashboard:read-sales");
        String dataScope = accessControlService.effectiveDataScope(identity);
        accessControlService.requireScopedIdentity(identity, dataScope);

        LocalDate today = LocalDate.now();
        LocalDate currentYearStart = today.withDayOfYear(1);
        LocalDate currentEnd = today.plusDays(1);
        LocalDate previousYearStart = currentYearStart.minusYears(1);
        LocalDate previousEnd = currentEnd.minusYears(1);
        LocalDate nextYearStart = currentYearStart.plusYears(1);

        SalesDashboardResponse.SalesComparison inbound = comparison(
                INBOUND_AT, APPROVAL_JOIN, currentYearStart, currentEnd, previousYearStart, previousEnd, identity, dataScope);
        SalesDashboardResponse.SalesComparison outbound = comparison(
                OUTBOUND_AT, "LEFT JOIN order_logistics l ON l.order_id = o.order_id",
                currentYearStart, currentEnd, previousYearStart, previousEnd, identity, dataScope);
        List<SalesDashboardResponse.MonthlySalesTrend> trend = monthlyTrend(
                currentYearStart, nextYearStart, previousYearStart, identity, dataScope);
        SalesDashboardResponse.MonthComparison monthComparison = monthComparison(today, identity, dataScope);

        return new SalesDashboardResponse(
                today.getYear(),
                today,
                CURRENCY,
                inbound,
                outbound,
                trend,
                monthComparison,
                SOURCE_NOTE,
                LocalDateTime.now());
    }

    private SalesDashboardResponse.MonthComparison monthComparison(
            LocalDate today, BootstrapIdentity identity, String dataScope) {
        YearMonth currentMonth = YearMonth.from(today);
        YearMonth previousMonth = currentMonth.minusMonths(1);
        int comparisonDayCount = Math.min(today.getDayOfMonth(), previousMonth.lengthOfMonth());
        LocalDate currentStart = currentMonth.atDay(1);
        LocalDate currentEnd = currentStart.plusDays(comparisonDayCount);
        LocalDate previousStart = previousMonth.atDay(1);
        LocalDate previousEnd = previousStart.plusDays(comparisonDayCount);

        SalesDashboardResponse.SalesComparison inbound = comparison(
                INBOUND_AT, APPROVAL_JOIN, currentStart, currentEnd, previousStart, previousEnd, identity, dataScope);
        SalesDashboardResponse.SalesComparison outbound = comparison(
                OUTBOUND_AT, "LEFT JOIN order_logistics l ON l.order_id = o.order_id",
                currentStart, currentEnd, previousStart, previousEnd, identity, dataScope);

        return new SalesDashboardResponse.MonthComparison(
                currentMonth,
                previousMonth,
                currentEnd.minusDays(1),
                comparisonDayCount,
                monthAmountComparison(inbound),
                monthAmountComparison(outbound),
                dailyMonthTrend(
                        comparisonDayCount,
                        currentStart,
                        currentEnd,
                        previousStart,
                        previousEnd,
                        identity,
                        dataScope));
    }

    private SalesDashboardResponse.MonthAmountComparison monthAmountComparison(
            SalesDashboardResponse.SalesComparison comparison) {
        return new SalesDashboardResponse.MonthAmountComparison(
                comparison.currentAmountCents(),
                comparison.previousYearAmountCents(),
                comparison.yearOverYearPercent(),
                comparison.currentOrderCount(),
                comparison.previousYearOrderCount(),
                comparison.currentAmountOrderCount(),
                comparison.previousYearAmountOrderCount());
    }

    private List<SalesDashboardResponse.DailyMonthSalesTrend> dailyMonthTrend(
            int dayCount,
            LocalDate currentStart,
            LocalDate currentEnd,
            LocalDate previousStart,
            LocalDate previousEnd,
            BootstrapIdentity identity,
            String dataScope) {
        long[] currentInbound = dailyCumulative(
                dayCount, INBOUND_AT, APPROVAL_JOIN, currentStart, currentEnd, identity, dataScope);
        long[] previousInbound = dailyCumulative(
                dayCount, INBOUND_AT, APPROVAL_JOIN, previousStart, previousEnd, identity, dataScope);
        long[] currentOutbound = dailyCumulative(
                dayCount, OUTBOUND_AT, "LEFT JOIN order_logistics l ON l.order_id = o.order_id",
                currentStart, currentEnd, identity, dataScope);
        long[] previousOutbound = dailyCumulative(
                dayCount, OUTBOUND_AT, "LEFT JOIN order_logistics l ON l.order_id = o.order_id",
                previousStart, previousEnd, identity, dataScope);

        List<SalesDashboardResponse.DailyMonthSalesTrend> result = new ArrayList<>(dayCount);
        for (int day = 1; day <= dayCount; day++) {
            int index = day - 1;
            result.add(new SalesDashboardResponse.DailyMonthSalesTrend(
                    day,
                    currentInbound[index],
                    previousInbound[index],
                    currentOutbound[index],
                    previousOutbound[index]));
        }
        return result;
    }

    private long[] dailyCumulative(
            int dayCount,
            String eventAt,
            String eventJoin,
            LocalDate start,
            LocalDate end,
            BootstrapIdentity identity,
            String dataScope) {
        long[] result = new long[dayCount];
        List<DailyRow> rows = bindScope(jdbcClient.sql("""
                        SELECT DAYOFMONTH(%1$s) AS event_day,
                               COALESCE(SUM(CASE
                                   WHEN b.amount_cent IS NOT NULL AND b.currency = :currency
                                   THEN b.amount_cent ELSE 0 END), 0) AS amount_cents
                        FROM orders o
                        LEFT JOIN order_bill b ON b.order_id = o.order_id
                        %2$s
                        WHERE %3$s
                          AND %1$s >= :startAt
                          AND %1$s < :endAt
                        GROUP BY DAYOFMONTH(%1$s)
                        ORDER BY event_day
                        """.formatted(eventAt, eventJoin, scopedWhereClause())), identity, dataScope)
                .param("currency", CURRENCY)
                .param("startAt", start.atStartOfDay())
                .param("endAt", end.atStartOfDay())
                .query((rs, rowNum) -> new DailyRow(rs.getInt("event_day"), rs.getLong("amount_cents")))
                .list();
        for (DailyRow row : rows) {
            int index = row.day() - 1;
            if (index >= 0 && index < result.length) {
                result[index] = row.amountCents();
            }
        }
        long cumulative = 0;
        for (int index = 0; index < result.length; index++) {
            cumulative += result[index];
            result[index] = cumulative;
        }
        return result;
    }

    private SalesDashboardResponse.SalesComparison comparison(
            String eventAt,
            String eventJoin,
            LocalDate currentStart,
            LocalDate currentEnd,
            LocalDate previousStart,
            LocalDate previousEnd,
            BootstrapIdentity identity,
            String dataScope) {
        ComparisonRow row = bindScope(jdbcClient.sql("""
                        SELECT
                            COALESCE(SUM(CASE
                                WHEN %1$s >= :currentStart AND %1$s < :currentEnd
                                  AND b.amount_cent IS NOT NULL AND b.currency = :currency
                                THEN b.amount_cent ELSE 0 END), 0) AS current_amount_cents,
                            COALESCE(SUM(CASE
                                WHEN %1$s >= :previousStart AND %1$s < :previousEnd
                                  AND b.amount_cent IS NOT NULL AND b.currency = :currency
                                THEN b.amount_cent ELSE 0 END), 0) AS previous_amount_cents,
                            COALESCE(SUM(CASE WHEN %1$s >= :currentStart AND %1$s < :currentEnd THEN 1 ELSE 0 END), 0)
                                AS current_order_count,
                            COALESCE(SUM(CASE WHEN %1$s >= :previousStart AND %1$s < :previousEnd THEN 1 ELSE 0 END), 0)
                                AS previous_order_count,
                            COALESCE(SUM(CASE
                                WHEN %1$s >= :currentStart AND %1$s < :currentEnd
                                  AND b.amount_cent IS NOT NULL AND b.currency = :currency
                                THEN 1 ELSE 0 END), 0) AS current_amount_order_count,
                            COALESCE(SUM(CASE
                                WHEN %1$s >= :previousStart AND %1$s < :previousEnd
                                  AND b.amount_cent IS NOT NULL AND b.currency = :currency
                                THEN 1 ELSE 0 END), 0) AS previous_amount_order_count
                        FROM orders o
                        LEFT JOIN order_bill b ON b.order_id = o.order_id
                        %2$s
                        WHERE %3$s
                        """.formatted(eventAt, eventJoin, scopedWhereClause())), identity, dataScope)
                .param("currency", CURRENCY)
                .param("currentStart", currentStart.atStartOfDay())
                .param("currentEnd", currentEnd.atStartOfDay())
                .param("previousStart", previousStart.atStartOfDay())
                .param("previousEnd", previousEnd.atStartOfDay())
                .query((rs, rowNum) -> new ComparisonRow(
                        rs.getLong("current_amount_cents"),
                        rs.getLong("previous_amount_cents"),
                        rs.getLong("current_order_count"),
                        rs.getLong("previous_order_count"),
                        rs.getLong("current_amount_order_count"),
                        rs.getLong("previous_amount_order_count")))
                .single();

        return new SalesDashboardResponse.SalesComparison(
                row.currentAmountCents(),
                row.previousAmountCents(),
                yearOverYearPercent(row.currentAmountCents(), row.previousAmountCents()),
                row.currentOrderCount(),
                row.previousOrderCount(),
                row.currentAmountOrderCount(),
                row.previousAmountOrderCount());
    }

    private List<SalesDashboardResponse.MonthlySalesTrend> monthlyTrend(
            LocalDate currentYearStart,
            LocalDate nextYearStart,
            LocalDate previousYearStart,
            BootstrapIdentity identity,
            String dataScope) {
        long[] inboundCurrent = new long[12];
        long[] outboundCurrent = new long[12];
        long[] inboundPrevious = new long[12];
        long[] outboundPrevious = new long[12];

        applyTrendRows(inboundCurrent, inboundPrevious, trendRows(
                INBOUND_AT, APPROVAL_JOIN, currentYearStart, nextYearStart, previousYearStart, identity, dataScope));
        applyTrendRows(outboundCurrent, outboundPrevious, trendRows(
                OUTBOUND_AT, "LEFT JOIN order_logistics l ON l.order_id = o.order_id",
                currentYearStart, nextYearStart, previousYearStart, identity, dataScope));

        List<SalesDashboardResponse.MonthlySalesTrend> result = new ArrayList<>(12);
        for (int month = 1; month <= 12; month++) {
            int index = month - 1;
            result.add(new SalesDashboardResponse.MonthlySalesTrend(
                    month,
                    inboundCurrent[index],
                    outboundCurrent[index],
                    inboundPrevious[index],
                    outboundPrevious[index]));
        }
        return result;
    }

    private List<TrendRow> trendRows(
            String eventAt,
            String eventJoin,
            LocalDate currentYearStart,
            LocalDate nextYearStart,
            LocalDate previousYearStart,
            BootstrapIdentity identity,
            String dataScope) {
        return bindScope(jdbcClient.sql("""
                        SELECT YEAR(%1$s) AS event_year,
                               MONTH(%1$s) AS event_month,
                               COALESCE(SUM(CASE
                                   WHEN b.amount_cent IS NOT NULL AND b.currency = :currency
                                   THEN b.amount_cent ELSE 0 END), 0) AS amount_cents
                        FROM orders o
                        LEFT JOIN order_bill b ON b.order_id = o.order_id
                        %2$s
                        WHERE %3$s
                          AND %1$s >= :previousYearStart
                          AND %1$s < :nextYearStart
                        GROUP BY YEAR(%1$s), MONTH(%1$s)
                        ORDER BY event_year, event_month
                        """.formatted(eventAt, eventJoin, scopedWhereClause())), identity, dataScope)
                .param("currency", CURRENCY)
                .param("previousYearStart", previousYearStart.atStartOfDay())
                .param("nextYearStart", nextYearStart.atStartOfDay())
                .query((rs, rowNum) -> new TrendRow(
                        rs.getInt("event_year"),
                        rs.getInt("event_month"),
                        rs.getLong("amount_cents")))
                .list();
    }

    private void applyTrendRows(long[] current, long[] previous, List<TrendRow> rows) {
        int currentYear = LocalDate.now().getYear();
        for (TrendRow row : rows) {
            int index = row.month() - 1;
            if (index < 0 || index >= 12) {
                continue;
            }
            if (row.year() == currentYear) {
                current[index] = row.amountCents();
            } else if (row.year() == currentYear - 1) {
                previous[index] = row.amountCents();
            }
        }
    }

    private Double yearOverYearPercent(long currentAmountCents, long previousAmountCents) {
        if (previousAmountCents <= 0) {
            return null;
        }
        return BigDecimal.valueOf(currentAmountCents - previousAmountCents)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(previousAmountCents), 2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private JdbcClient.StatementSpec bindScope(
            JdbcClient.StatementSpec spec,
            BootstrapIdentity identity,
            String dataScope) {
        return spec.param("dataScope", dataScope)
                .param("userId", identity.userId())
                .param("clinicId", identity.clinicId());
    }

    private String scopedWhereClause() {
        return """
                (
                    :dataScope = 'ALL'
                    OR (:dataScope = 'CLINIC'
                        AND (o.clinic_id = :clinicId OR o.doctor_user_id = :userId))
                    OR (:dataScope = 'SELF'
                        AND (
                            o.doctor_user_id = :userId
                            OR o.cs_user_id = :userId
                            OR EXISTS (
                                SELECT 1
                                FROM order_process_instance scoped_i
                                JOIN order_process_node scoped_n
                                  ON scoped_n.instance_id = scoped_i.instance_id
                                WHERE scoped_i.order_id = o.order_id
                                  AND scoped_n.assigned_user_id = :userId
                            )
                        ))
                )
                """;
    }

    private record ComparisonRow(
            long currentAmountCents,
            long previousAmountCents,
            long currentOrderCount,
            long previousOrderCount,
            long currentAmountOrderCount,
            long previousAmountOrderCount) {
    }

    private record TrendRow(int year, int month, long amountCents) {
    }

    private record DailyRow(int day, long amountCents) {
    }
}
