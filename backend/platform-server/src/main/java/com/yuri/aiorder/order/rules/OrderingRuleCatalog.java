package com.yuri.aiorder.order.rules;

import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 交期规则引擎读取的配置数据（{@code ordering_rule_config}）。
 *
 * <p>各产品标准制作周期、快递在途天数、医生确认宽限期都是**客户尚未提供的数据**（CP 项）。
 * 引擎不写死这些数字，全部从这张表取；每条规则带 {@code confirmation_status}，
 * 只要参与本次计算的规则里有一条是 {@code PLACEHOLDER}，算出来的交期就整体标为
 * {@code PLACEHOLDER}——界面据此标注「待确认」，不得表现为正式承诺交期。
 *
 * <p>不做缓存，理由与 {@code RolePermissionCatalog} 相同：管理端改完配置必须立即生效。
 */
@Component
public class OrderingRuleCatalog {

    public static final String TYPE_PRODUCT_CYCLE = "PRODUCT_CYCLE";
    public static final String TYPE_PRIORITY_CAP = "PRIORITY_CAP";
    public static final String TYPE_PROCESS_CONFIRMATION = "PROCESS_CONFIRMATION";
    public static final String TYPE_SHIPPING_TRANSIT = "SHIPPING_TRANSIT";

    public static final String KEY_DEFAULT_PRODUCT_CYCLE = "__DEFAULT__";
    public static final String KEY_PER_ITEM_DAYS = "PER_ITEM_DAYS";
    public static final String KEY_DOCTOR_GRACE_DAYS = "DOCTOR_GRACE_DAYS";

    /** 配置整张表都被删空时的最后兜底，只为不让下单链路崩掉；命中即视为占位值。 */
    private static final Rule FALLBACK_CYCLE =
            new Rule(TYPE_PRODUCT_CYCLE, KEY_DEFAULT_PRODUCT_CYCLE, 7,
                    OrderRuleVocabulary.ESTIMATE_PLACEHOLDER, "兜底周期（配置缺失）");
    private static final Rule FALLBACK_PER_ITEM =
            new Rule(TYPE_PROCESS_CONFIRMATION, KEY_PER_ITEM_DAYS, 1,
                    OrderRuleVocabulary.ESTIMATE_CONFIRMED, "每项过程确认追加天数（配置缺失）");
    private static final Rule FALLBACK_GRACE =
            new Rule(TYPE_PROCESS_CONFIRMATION, KEY_DOCTOR_GRACE_DAYS, 2,
                    OrderRuleVocabulary.ESTIMATE_PLACEHOLDER, "医生确认宽限天数（配置缺失）");
    private static final Rule FALLBACK_ZERO_TRANSIT =
            new Rule(TYPE_SHIPPING_TRANSIT, "UNKNOWN", 0,
                    OrderRuleVocabulary.ESTIMATE_PLACEHOLDER, "在途天数（配置缺失）");
    private static final Rule FALLBACK_NO_CAP =
            new Rule(TYPE_PRIORITY_CAP, OrderRuleVocabulary.DEFAULT_PRIORITY, -1,
                    OrderRuleVocabulary.ESTIMATE_CONFIRMED, "不设制作天数上限（配置缺失）");

    private final JdbcClient jdbcClient;

    public OrderingRuleCatalog(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** 产品标准制作周期；产品类型未配置时退回 {@code __DEFAULT__}，不阻塞下单。 */
    public Rule productCycle(String workflowProductType) {
        String key = workflowProductType == null || workflowProductType.isBlank()
                ? KEY_DEFAULT_PRODUCT_CYCLE
                : workflowProductType.trim().toUpperCase(Locale.ROOT);
        Rule rule = find(TYPE_PRODUCT_CYCLE, key);
        if (rule != null) {
            return rule;
        }
        Rule fallback = find(TYPE_PRODUCT_CYCLE, KEY_DEFAULT_PRODUCT_CYCLE);
        return fallback == null ? FALLBACK_CYCLE : fallback;
    }

    /** 制作天数上限；{@code numeric_value < 0} 表示不设上限。 */
    public Rule priorityCap(String priorityCode) {
        Rule rule = find(TYPE_PRIORITY_CAP, priorityCode);
        return rule == null ? FALLBACK_NO_CAP : rule;
    }

    public Rule perProcessConfirmationDays() {
        Rule rule = find(TYPE_PROCESS_CONFIRMATION, KEY_PER_ITEM_DAYS);
        return rule == null ? FALLBACK_PER_ITEM : rule;
    }

    public Rule doctorConfirmationGraceDays() {
        Rule rule = find(TYPE_PROCESS_CONFIRMATION, KEY_DOCTOR_GRACE_DAYS);
        return rule == null ? FALLBACK_GRACE : rule;
    }

    public Rule shippingTransit(String shippingMethod) {
        Rule rule = find(TYPE_SHIPPING_TRANSIT, shippingMethod);
        return rule == null ? FALLBACK_ZERO_TRANSIT : rule;
    }

    public List<Rule> listAll() {
        return jdbcClient.sql("""
                        SELECT rule_type, rule_key, numeric_value, confirmation_status, display_name
                        FROM ordering_rule_config
                        WHERE status = 'ACTIVE'
                        ORDER BY rule_type, rule_key
                        """)
                .query(OrderingRuleCatalog::mapRule)
                .list();
    }

    private Rule find(String ruleType, String ruleKey) {
        if (ruleKey == null || ruleKey.isBlank()) {
            return null;
        }
        return jdbcClient.sql("""
                        SELECT rule_type, rule_key, numeric_value, confirmation_status, display_name
                        FROM ordering_rule_config
                        WHERE rule_type = :ruleType
                          AND rule_key = :ruleKey
                          AND status = 'ACTIVE'
                        """)
                .param("ruleType", ruleType)
                .param("ruleKey", ruleKey.trim().toUpperCase(Locale.ROOT))
                .query(OrderingRuleCatalog::mapRule)
                .optional()
                .orElse(null);
    }

    private static Rule mapRule(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Rule(
                rs.getString("rule_type"),
                rs.getString("rule_key"),
                rs.getInt("numeric_value"),
                rs.getString("confirmation_status"),
                rs.getString("display_name"));
    }

    public record Rule(
            String ruleType,
            String ruleKey,
            int days,
            String confirmationStatus,
            String displayName) {

        public boolean isPlaceholder() {
            return !OrderRuleVocabulary.ESTIMATE_CONFIRMED.equals(confirmationStatus);
        }
    }
}
