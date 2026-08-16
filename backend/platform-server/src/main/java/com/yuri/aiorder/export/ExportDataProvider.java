package com.yuri.aiorder.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.export.ExportDatasetCatalog.Dataset;
import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 各数据集的取数实现。**这是唯一写导出 SQL 的地方。**
 *
 * <p>两条纪律：
 * <ul>
 *   <li>订单派生的数据集必须带数据范围条件。导出如果绕过 {@code data_scope}，
 *       就等于给了一条「看不到的数据也能导出来」的旁路，比界面上多一个按钮严重得多。</li>
 *   <li>筛选条件只认白名单里的键，值一律走绑定参数——导出的入参来自界面，
 *       拼字符串就是把 SQL 注入面直接开在数据出口上。</li>
 * </ul>
 */
@Component
public class ExportDataProvider {

    /** 与 {@code export_dataset} 表必须一一对应，启动时校验。 */
    private static final Set<String> IMPLEMENTED_DATASETS = Set.of(
            "CUSTOMER_PROFILE",
            "CUSTOMER_SHIPPING_ADDRESS",
            "ORDER_BILL",
            "PRODUCT_PRICE",
            "ORDER_LIST",
            "PRODUCTION_TASK",
            "REWORK_RECORD");

    private static final int MAX_ROWS = 50_000;

    private final JdbcClient jdbcClient;
    private final ExportDatasetCatalog catalog;

    public ExportDataProvider(JdbcClient jdbcClient, ExportDatasetCatalog catalog) {
        this.jdbcClient = jdbcClient;
        this.catalog = catalog;
    }

    /**
     * 目录与实现必须一一对应。少了实现就是「界面上能选、点了报错」，
     * 多了实现就是「有取数能力但没登记敏感级别」——后者会让一个数据集绕过审批分类。
     * 两种都在启动时拦下，而不是等有人点导出才发现。
     */
    @PostConstruct
    public void validateAgainstCatalog() {
        Set<String> configured = catalog.listActive().stream()
                .map(Dataset::datasetCode)
                .collect(java.util.stream.Collectors.toSet());
        List<String> missingImplementation = configured.stream()
                .filter(code -> !IMPLEMENTED_DATASETS.contains(code))
                .sorted()
                .toList();
        List<String> missingConfiguration = IMPLEMENTED_DATASETS.stream()
                .filter(code -> !configured.contains(code))
                .sorted()
                .toList();
        if (!missingImplementation.isEmpty() || !missingConfiguration.isEmpty()) {
            throw new IllegalStateException(
                    "export_dataset 与 ExportDataProvider 不一致；缺实现=" + missingImplementation
                            + "，缺配置=" + missingConfiguration);
        }
    }

    public Rows fetch(Dataset dataset, JsonNode filters, BootstrapIdentity identity, String dataScope) {
        Filters parsed = Filters.parse(filters);
        return switch (dataset.datasetCode()) {
            case "CUSTOMER_PROFILE" -> customerProfile(parsed);
            case "CUSTOMER_SHIPPING_ADDRESS" -> customerShippingAddress(parsed);
            case "ORDER_BILL" -> orderBill(parsed, identity, dataScope);
            case "PRODUCT_PRICE" -> productPrice(parsed);
            case "ORDER_LIST" -> orderList(parsed, identity, dataScope);
            case "PRODUCTION_TASK" -> productionTask(parsed, identity, dataScope);
            case "REWORK_RECORD" -> reworkRecord(parsed, identity, dataScope);
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "unsupported export dataset: " + dataset.datasetCode());
        };
    }

    // ------------------------------------------------------------------
    // 敏感类：客户信息 / 地址 / 账单 / 价格
    // ------------------------------------------------------------------

    private Rows customerProfile(Filters filters) {
        List<List<String>> rows = jdbcClient.sql("""
                        SELECT clinic_code, clinic_name, contact_name, contact_phone,
                               contact_email, business_region, salesperson, customer_type,
                               settlement_type, status
                        FROM clinic
                        WHERE (:status IS NULL OR status = :status)
                        ORDER BY clinic_id
                        LIMIT :maxRows
                        """)
                .param("status", filters.status())
                .param("maxRows", MAX_ROWS)
                .query((rs, rowNum) -> List.of(
                        text(rs.getString("clinic_code")), text(rs.getString("clinic_name")),
                        text(rs.getString("contact_name")), text(rs.getString("contact_phone")),
                        text(rs.getString("contact_email")), text(rs.getString("business_region")),
                        text(rs.getString("salesperson")), text(rs.getString("customer_type")),
                        text(rs.getString("settlement_type")), text(rs.getString("status"))))
                .list();
        return new Rows(rows);
    }

    private Rows customerShippingAddress(Filters filters) {
        List<List<String>> rows = jdbcClient.sql("""
                        SELECT clinic.clinic_code, clinic.clinic_name, address.address_label,
                               address.recipient_name, address.recipient_phone, address.province,
                               address.city, address.district, address.detail_address,
                               address.default_flag
                        FROM clinic_shipping_address address
                        JOIN clinic ON clinic.clinic_id = address.clinic_id
                        WHERE address.status = 'ACTIVE'
                          AND (:clinicId IS NULL OR address.clinic_id = :clinicId)
                        ORDER BY address.clinic_id, address.address_id
                        LIMIT :maxRows
                        """)
                .param("clinicId", filters.clinicId())
                .param("maxRows", MAX_ROWS)
                .query((rs, rowNum) -> List.of(
                        text(rs.getString("clinic_code")), text(rs.getString("clinic_name")),
                        text(rs.getString("address_label")), text(rs.getString("recipient_name")),
                        text(rs.getString("recipient_phone")), text(rs.getString("province")),
                        text(rs.getString("city")), text(rs.getString("district")),
                        text(rs.getString("detail_address")),
                        rs.getInt("default_flag") == 1 ? "是" : "否"))
                .list();
        return new Rows(rows);
    }

    private Rows orderBill(Filters filters, BootstrapIdentity identity, String dataScope) {
        List<List<String>> rows = scoped("""
                        SELECT o.order_no, c.clinic_name, b.bill_no, b.bill_status,
                               b.payment_status, b.amount_cent, b.currency, b.created_at
                        FROM order_bill b
                        JOIN orders o ON o.order_id = b.order_id
                        JOIN clinic c ON c.clinic_id = o.clinic_id
                        WHERE %s
                          AND (:createdFrom IS NULL OR b.created_at >= :createdFrom)
                          AND (:createdTo IS NULL OR b.created_at < :createdTo)
                        ORDER BY b.bill_id
                        LIMIT :maxRows
                        """, filters, identity, dataScope)
                .query((rs, rowNum) -> List.of(
                        text(rs.getString("order_no")), text(rs.getString("clinic_name")),
                        text(rs.getString("bill_no")), text(rs.getString("bill_status")),
                        text(rs.getString("payment_status")), money(rs.getObject("amount_cent", Long.class)),
                        text(rs.getString("currency")), text(rs.getString("created_at"))))
                .list();
        return new Rows(rows);
    }

    private Rows productPrice(Filters filters) {
        List<List<String>> rows = jdbcClient.sql("""
                        SELECT product.product_code, product.display_name,
                               category.display_name AS category_name, product.pricing_status,
                               product.base_price_cents, product.currency, product.status
                        FROM catalog_product_v2 product
                        JOIN catalog_category_v2 category
                          ON category.category_id = product.category_id
                        JOIN catalog_config_version version
                          ON version.config_version_id = product.config_version_id
                        WHERE version.publication_status = 'ACTIVE'
                          AND (:status IS NULL OR product.status = :status)
                        ORDER BY product.sort_order, product.product_id
                        LIMIT :maxRows
                        """)
                .param("status", filters.status())
                .param("maxRows", MAX_ROWS)
                .query((rs, rowNum) -> List.of(
                        text(rs.getString("product_code")), text(rs.getString("display_name")),
                        text(rs.getString("category_name")), text(rs.getString("pricing_status")),
                        money(rs.getObject("base_price_cents", Long.class)),
                        text(rs.getString("currency")), text(rs.getString("status"))))
                .list();
        return new Rows(rows);
    }

    // ------------------------------------------------------------------
    // 非敏感类：直接导出并留痕
    // ------------------------------------------------------------------

    private Rows orderList(Filters filters, BootstrapIdentity identity, String dataScope) {
        List<List<String>> rows = scoped("""
                        SELECT o.order_no, c.clinic_name, patient.patient_name, o.product_type,
                               o.internal_status, o.external_status, o.created_at, o.updated_at
                        FROM orders o
                        JOIN clinic c ON c.clinic_id = o.clinic_id
                        LEFT JOIN patient_record patient ON patient.patient_id = o.patient_id
                        WHERE %s
                          AND (:createdFrom IS NULL OR o.created_at >= :createdFrom)
                          AND (:createdTo IS NULL OR o.created_at < :createdTo)
                          AND (:status IS NULL OR o.internal_status = :status)
                        ORDER BY o.order_id
                        LIMIT :maxRows
                        """, filters, identity, dataScope)
                .query((rs, rowNum) -> List.of(
                        text(rs.getString("order_no")), text(rs.getString("clinic_name")),
                        text(rs.getString("patient_name")), text(rs.getString("product_type")),
                        text(rs.getString("internal_status")), text(rs.getString("external_status")),
                        text(rs.getString("created_at")), text(rs.getString("updated_at"))))
                .list();
        return new Rows(rows);
    }

    private Rows productionTask(Filters filters, BootstrapIdentity identity, String dataScope) {
        List<List<String>> rows = scoped("""
                        SELECT o.order_no, node.node_code, node.process_name, node.node_status,
                               operator.display_name AS operator_name,
                               node.started_at, node.completed_at
                        FROM order_process_node node
                        JOIN order_process_instance instance
                          ON instance.instance_id = node.instance_id
                        JOIN orders o ON o.order_id = instance.order_id
                        LEFT JOIN system_user operator ON operator.user_id = node.assigned_user_id
                        WHERE %s
                          AND (:createdFrom IS NULL OR node.created_at >= :createdFrom)
                          AND (:createdTo IS NULL OR node.created_at < :createdTo)
                        ORDER BY node.node_instance_id
                        LIMIT :maxRows
                        """, filters, identity, dataScope)
                .query((rs, rowNum) -> List.of(
                        text(rs.getString("order_no")), text(rs.getString("node_code")),
                        text(rs.getString("process_name")), text(rs.getString("node_status")),
                        text(rs.getString("operator_name")), text(rs.getString("started_at")),
                        text(rs.getString("completed_at"))))
                .list();
        return new Rows(rows);
    }

    private Rows reworkRecord(Filters filters, BootstrapIdentity identity, String dataScope) {
        List<List<String>> rows = scoped("""
                        SELECT rework.rework_id, o.order_no, rework.reason_category,
                               rework.responsibility_type, rework.status,
                               rework.created_at, rework.closed_at
                        FROM rework_record rework
                        JOIN orders o ON o.order_id = rework.order_id
                        WHERE %s
                          AND (:createdFrom IS NULL OR rework.created_at >= :createdFrom)
                          AND (:createdTo IS NULL OR rework.created_at < :createdTo)
                        ORDER BY rework.rework_id
                        LIMIT :maxRows
                        """, filters, identity, dataScope)
                .query((rs, rowNum) -> List.of(
                        String.valueOf(rs.getLong("rework_id")), text(rs.getString("order_no")),
                        text(rs.getString("reason_category")), text(rs.getString("responsibility_type")),
                        text(rs.getString("status")), text(rs.getString("created_at")),
                        text(rs.getString("closed_at"))))
                .list();
        return new Rows(rows);
    }

    // ------------------------------------------------------------------

    /**
     * 订单派生数据集统一套用与 {@code OrderProjectionQueryService} 相同的数据范围条件，
     * 保证「导出看到的」不会多于「界面上看得到的」。
     */
    private JdbcClient.StatementSpec scoped(
            String sqlTemplate, Filters filters, BootstrapIdentity identity, String dataScope) {
        String scopeClause = """
                (
                    :dataScope = 'ALL'
                    OR (:dataScope = 'CLINIC'
                        AND (o.clinic_id = :identityClinicId OR o.doctor_user_id = :identityUserId))
                    OR (:dataScope = 'SELF'
                        AND (o.doctor_user_id = :identityUserId OR o.cs_user_id = :identityUserId))
                )
                """;
        return jdbcClient.sql(sqlTemplate.formatted(scopeClause))
                .param("dataScope", dataScope)
                .param("identityUserId", identity.userId())
                .param("identityClinicId", identity.clinicId())
                .param("createdFrom", filters.createdFrom())
                .param("createdTo", filters.createdToExclusive())
                .param("status", filters.status())
                .param("maxRows", MAX_ROWS);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String money(Long cents) {
        return cents == null ? "" : java.math.BigDecimal.valueOf(cents, 2).toPlainString();
    }

    /** 导出结果。表头取自 {@code export_dataset.field_list}，与留痕记录的字段清单是同一份。 */
    public record Rows(List<List<String>> rows) {

        public int rowCount() {
            return rows.size();
        }
    }

    /**
     * 筛选条件白名单。只认这四个键，其它一律忽略——导出入参来自界面，
     * 不能让它决定 SQL 结构。
     */
    record Filters(LocalDate createdFrom, LocalDate createdToInclusive, String status, Long clinicId) {

        private static final Set<String> ALLOWED_KEYS =
                Set.of("created_from", "created_to", "status", "clinic_id");

        static Filters parse(JsonNode node) {
            if (node == null || !node.isObject()) {
                return new Filters(null, null, null, null);
            }
            List<String> unknown = new ArrayList<>();
            node.fieldNames().forEachRemaining(name -> {
                if (!ALLOWED_KEYS.contains(name)) {
                    unknown.add(name);
                }
            });
            if (!unknown.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "unsupported export filter(s): " + unknown);
            }
            return new Filters(
                    date(node, "created_from"),
                    date(node, "created_to"),
                    string(node, "status"),
                    longValue(node, "clinic_id"));
        }

        /**
         * 查询用的上界。{@code created_to} 对使用者是「含当天」，落到 SQL 上必须是次日零点的开区间，
         * 否则当天 00:00 之后的数据会被整段漏掉——而漏数据的导出不会报错，只会少几行。
         */
        LocalDate createdToExclusive() {
            return createdToInclusive == null ? null : createdToInclusive.plusDays(1);
        }

        /** 落进留痕的「导出范围」。用 LinkedHashMap 保证审计里键的顺序稳定、可比对。 */
        Map<String, Object> asAuditRange() {
            Map<String, Object> range = new LinkedHashMap<>();
            range.put("created_from", createdFrom == null ? null : createdFrom.toString());
            range.put("created_to", createdToInclusive == null ? null : createdToInclusive.toString());
            range.put("status", status);
            range.put("clinic_id", clinicId);
            return range;
        }

        private static LocalDate date(JsonNode node, String key) {
            String value = string(node, key);
            if (value == null) {
                return null;
            }
            try {
                return LocalDate.parse(value);
            } catch (DateTimeParseException ex) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, key + " must be an ISO date", ex);
            }
        }

        private static String string(JsonNode node, String key) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull() || value.asText("").isBlank()) {
                return null;
            }
            return value.asText().trim();
        }

        private static Long longValue(JsonNode node, String key) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull() || !value.canConvertToLong()) {
                return null;
            }
            return value.longValue();
        }
    }
}
