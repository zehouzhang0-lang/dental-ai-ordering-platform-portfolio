package com.yuri.aiorder.dashboard;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.AccessControlService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.EnumSet;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class PhaseOneDashboardService {

    private static final String SOURCE_NOTE = "本地月度趋势接口 / 客户排名第一段";
    // JSON response fields: current_month, previous_month, monthly_order_delta, top_customers,
    // production_exception_count, pending_question_count.
    private static final String ITEM_COUNT_SQL = """
            CASE
                WHEN JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.item_count')) REGEXP '^[0-9]+$'
                    THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.item_count')) AS UNSIGNED)
                WHEN JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.quantity')) REGEXP '^[0-9]+$'
                    THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.quantity')) AS UNSIGNED)
                WHEN JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.qty')) REGEXP '^[0-9]+$'
                    THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.qty')) AS UNSIGNED)
                WHEN JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.case_count')) REGEXP '^[0-9]+$'
                    THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.case_count')) AS UNSIGNED)
                ELSE 1
            END
            """;

    private final JdbcClient jdbcClient;
    private final AccessControlService accessControlService;

    public PhaseOneDashboardService(JdbcClient jdbcClient, AccessControlService accessControlService) {
        this.jdbcClient = jdbcClient;
        this.accessControlService = accessControlService;
    }

    public PhaseOneDashboardResponse getPhaseOneAbDashboard(BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, "dashboard:read-internal", "phase-one dashboard requires dashboard:read-internal");
        String dataScope = accessControlService.effectiveDataScope(identity);
        accessControlService.requireScopedIdentity(identity, dataScope);

        YearMonth currentMonth = YearMonth.now();
        YearMonth previousMonth = currentMonth.minusMonths(1);
        LocalDate currentStart = currentMonth.atDay(1);
        LocalDate nextStart = currentMonth.plusMonths(1).atDay(1);
        LocalDate previousStart = previousMonth.atDay(1);

        PhaseOneDashboardResponse.MonthSummary current = monthSummary(
                currentMonth, currentStart, nextStart, identity, dataScope);
        PhaseOneDashboardResponse.MonthSummary previous = monthSummary(
                previousMonth, previousStart, currentStart, identity, dataScope);
        List<PhaseOneDashboardResponse.CustomerRanking> topCustomers = topCustomers(
                previousStart, currentStart, nextStart, identity, dataScope);
        long productionExceptionCount = productionExceptionCount(currentStart, nextStart, identity, dataScope);
        long pendingQuestionCount = pendingQuestionCount(currentStart, nextStart, identity, dataScope);
        int shippingRate = shippingRate(currentStart, nextStart, identity, dataScope);
        int previousMonthShippingRate = shippingRate(previousStart, currentStart, identity, dataScope);
        LocalDate currentWeekStart = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        int previousWeekShippingRate = shippingRate(
                currentWeekStart.minusWeeks(1), currentWeekStart, identity, dataScope);
        int completionRate = completionRate(currentStart, nextStart, identity, dataScope);

        return new PhaseOneDashboardResponse(
                current,
                previous,
                current.orderCount() - previous.orderCount(),
                current.itemCount() - previous.itemCount(),
                topCustomers,
                productionExceptionCount,
                pendingQuestionCount,
                shippingRate,
                previousMonthShippingRate,
                previousWeekShippingRate,
                completionRate,
                SOURCE_NOTE,
                LocalDateTime.now());
    }

    private PhaseOneDashboardResponse.MonthSummary monthSummary(
            YearMonth month,
            LocalDate start,
            LocalDate end,
            BootstrapIdentity identity,
            String dataScope) {
        SummaryRow row = bindScopeAndWindow(jdbcClient.sql("""
                        SELECT COUNT(*) AS order_count,
                               COALESCE(SUM(%s), 0) AS item_count
                        FROM orders o
                        WHERE %s
                          AND o.created_at >= :startAt
                          AND o.created_at < :endAt
                        """.formatted(ITEM_COUNT_SQL, scopedWhereClause())),
                identity, dataScope, start, end)
                .query((rs, rowNum) -> new SummaryRow(rs.getLong("order_count"), rs.getLong("item_count")))
                .single();
        return new PhaseOneDashboardResponse.MonthSummary(month.toString(), row.orderCount(), row.itemCount());
    }

    /**
     * 十大客户排名：按本月订单数量 / 件数排名（一期 B 类已确认口径），同时带出上月同口径数据作对照。
     * 上月窗口只作对照，不参与排名，因此保留 HAVING 过滤掉本月无订单的客户。
     */
    private List<PhaseOneDashboardResponse.CustomerRanking> topCustomers(
            LocalDate previousStart,
            LocalDate currentStart,
            LocalDate nextStart,
            BootstrapIdentity identity,
            String dataScope) {
        return bindScope(jdbcClient.sql("""
                        SELECT o.clinic_id,
                               c.clinic_name,
                               COALESCE(SUM(CASE WHEN o.created_at >= :currentStartAt THEN 1 ELSE 0 END), 0)
                                   AS order_count,
                               COALESCE(SUM(CASE WHEN o.created_at >= :currentStartAt THEN %s ELSE 0 END), 0)
                                   AS item_count,
                               COALESCE(SUM(CASE WHEN o.created_at < :currentStartAt THEN 1 ELSE 0 END), 0)
                                   AS previous_order_count,
                               COALESCE(SUM(CASE WHEN o.created_at < :currentStartAt THEN %s ELSE 0 END), 0)
                                   AS previous_item_count
                        FROM orders o
                        JOIN clinic c ON c.clinic_id = o.clinic_id
                        WHERE %s
                          AND o.created_at >= :previousStartAt
                          AND o.created_at < :nextStartAt
                        GROUP BY o.clinic_id, c.clinic_name
                        HAVING order_count > 0
                        ORDER BY order_count DESC, item_count DESC, o.clinic_id ASC
                        LIMIT 10
                        """.formatted(ITEM_COUNT_SQL, ITEM_COUNT_SQL, scopedWhereClause())), identity, dataScope)
                .param("previousStartAt", previousStart.atStartOfDay())
                .param("currentStartAt", currentStart.atStartOfDay())
                .param("nextStartAt", nextStart.atStartOfDay())
                .query((rs, rowNum) -> {
                    long orderCount = rs.getLong("order_count");
                    long itemCount = rs.getLong("item_count");
                    long previousOrderCount = rs.getLong("previous_order_count");
                    long previousItemCount = rs.getLong("previous_item_count");
                    return new PhaseOneDashboardResponse.CustomerRanking(
                            rs.getLong("clinic_id"),
                            rs.getString("clinic_name"),
                            orderCount,
                            itemCount,
                            previousOrderCount,
                            previousItemCount,
                            orderCount - previousOrderCount,
                            itemCount - previousItemCount);
                })
                .list();
    }

    private long productionExceptionCount(LocalDate start, LocalDate end, BootstrapIdentity identity, String dataScope) {
        return bindScopeAndWindow(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM orders o
                        WHERE %s
                          AND o.created_at >= :startAt
                          AND o.created_at < :endAt
                          AND o.internal_status IN ('PENDING_PRODUCTION_REVIEW', 'PROCESS_INSTANCE_CREATED', 'PRODUCING', 'REWORKING')
                        """.formatted(scopedWhereClause())),
                identity, dataScope, start, end)
                .query(Long.class)
                .single();
    }

    private long pendingQuestionCount(LocalDate start, LocalDate end, BootstrapIdentity identity, String dataScope) {
        long pendingDoctorConfirm = bindScopeAndWindow(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM orders o
                        WHERE %s
                          AND o.created_at >= :startAt
                          AND o.created_at < :endAt
                          AND o.external_status = 'PENDING_DOCTOR_CONFIRM'
                        """.formatted(scopedWhereClause())),
                identity, dataScope, start, end)
                .query(Long.class)
                .single();
        long pendingMessages = bindScopeAndWindow(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_message m
                        JOIN orders o ON o.order_id = m.order_id
                        WHERE %s
                          AND o.created_at >= :startAt
                          AND o.created_at < :endAt
                          AND m.review_status = 'PENDING_REVIEW'
                        """.formatted(scopedWhereClause())),
                identity, dataScope, start, end)
                .query(Long.class)
                .single();
        return pendingDoctorConfirm + pendingMessages;
    }

    private int shippingRate(LocalDate start, LocalDate end, BootstrapIdentity identity, String dataScope) {
        SummaryRow row = bindScopeAndWindow(jdbcClient.sql("""
                        SELECT COUNT(*) AS order_count,
                               COALESCE(SUM(CASE
                                   WHEN l.logistics_status IN ('SHIPPED', 'IN_TRANSIT', 'DELIVERED') THEN 1
                                   ELSE 0
                               END), 0) AS item_count
                        FROM orders o
                        LEFT JOIN order_logistics l ON l.order_id = o.order_id
                        WHERE %s
                          AND o.created_at >= :startAt
                          AND o.created_at < :endAt
                        """.formatted(scopedWhereClause())),
                identity, dataScope, start, end)
                .query((rs, rowNum) -> new SummaryRow(rs.getLong("order_count"), rs.getLong("item_count")))
                .single();
        return percentage(row.itemCount(), row.orderCount());
    }

    private int completionRate(LocalDate start, LocalDate end, BootstrapIdentity identity, String dataScope) {
        SummaryRow row = bindScopeAndWindow(jdbcClient.sql("""
                        SELECT COUNT(*) AS order_count,
                               COALESCE(SUM(CASE
                                   WHEN o.internal_status IN ('COMPLETED', 'SHIPPED')
                                     OR o.external_status IN ('COMPLETED', 'SHIPPED', 'RECEIVED') THEN 1
                                   ELSE 0
                               END), 0) AS item_count
                        FROM orders o
                        WHERE %s
                          AND o.created_at >= :startAt
                          AND o.created_at < :endAt
                        """.formatted(scopedWhereClause())),
                identity, dataScope, start, end)
                .query((rs, rowNum) -> new SummaryRow(rs.getLong("order_count"), rs.getLong("item_count")))
                .single();
        return percentage(row.itemCount(), row.orderCount());
    }

    private JdbcClient.StatementSpec bindScopeAndWindow(
            JdbcClient.StatementSpec spec,
            BootstrapIdentity identity,
            String dataScope,
            LocalDate start,
            LocalDate end) {
        return bindScope(spec, identity, dataScope)
                .param("startAt", start.atStartOfDay())
                .param("endAt", end.atStartOfDay());
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

    private int percentage(long value, long total) {
        if (total <= 0) {
            return 0;
        }
        return Math.round((value * 100.0f) / total);
    }

    private record SummaryRow(long orderCount, long itemCount) {
    }
}
