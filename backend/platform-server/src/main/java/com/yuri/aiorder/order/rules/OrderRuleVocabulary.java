package com.yuri.aiorder.order.rules;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 下单规则的合法值域。**这个类是唯一权威**，不是前端常量、不是迁移 SQL、不是种子。
 *
 * <p>三组词汇在任务书、客户确认表、前端三处用词不一致，这里统一记一次：
 * <ul>
 *   <li>{@code priority}（前端字段 {@code case_priority}，界面标签「订单周期」，任务书称「订单类型」）：
 *       正常出货周期 / 3 天加急 / 当天出货，影响制作天数上限；</li>
 *   <li>{@code orderType}（前端字段 {@code order_type}，界面标签「订单类型」，任务书称「产品类型」）：
 *       网络 / 印模 / 返工 / 退货 / 仅设计，其中印模、返工、退货需要回寄运单号；</li>
 *   <li>{@code shippingMethod}（前端字段 {@code shipping_method}，界面标签「运输类型」）：
 *       快递 / 业务员配送 / 自取，影响在途天数。</li>
 * </ul>
 *
 * <p>前端字段名保持不变——为统一叫法去改 18k 行的下单向导，收益不抵风险。
 *
 * <p>值缺省时按默认值处理，值存在但不认识时直接 400。缺省容忍是为了兼容 F 批次之前创建的订单；
 * 「认识但不处理」才是本批次要消除的问题，所以不能反过来把未知值静默当默认值。
 */
public final class OrderRuleVocabulary {

    public static final String FORM_KEY_ORDER_TYPE = "order_type";
    public static final String FORM_KEY_PRIORITY = "case_priority";
    public static final String FORM_KEY_SHIPPING_METHOD = "shipping_method";
    public static final String FORM_KEY_INBOUND_TRACKING_NO = "inbound_tracking_no";
    public static final String FORM_KEY_REQUIRED_DELIVERY_DATE = "required_delivery_date";
    public static final String FORM_KEY_TRY_IN_REQUIRED = "try_in_required";
    public static final String FORM_KEY_PROCESS_REVIEWS = "process_reviews";

    public static final String DEFAULT_ORDER_TYPE = "ONLINE";
    public static final String DEFAULT_PRIORITY = "NORMAL";
    public static final String DEFAULT_SHIPPING_METHOD = "COURIER";

    public static final Set<String> ORDER_TYPES =
            Set.of("ONLINE", "IMPRESSION", "REWORK", "RETURN", "DESIGN_ONLY");

    /**
     * 需要回寄运单号的订单类型。
     *
     * <p>任务书 Scope 写的是「后三类」（返工/退货/仅设计），Acceptance 写的是「印模/返工/退货」，
     * 两处不一致。此处按 Acceptance 与前端现有校验取印模/返工/退货——仅设计订单不寄实体模型，
     * 要求运单号会拦住正常下单。该出入已在任务文件中登记，需与客户复核。
     */
    public static final Set<String> ORDER_TYPES_REQUIRING_INBOUND_TRACKING =
            Set.of("IMPRESSION", "REWORK", "RETURN");

    public static final Set<String> PRIORITIES = Set.of("NORMAL", "RUSH_3_DAYS", "SAME_DAY");

    public static final Set<String> SHIPPING_METHODS =
            Set.of("COURIER", "SALES_DELIVERY", "SELF_PICKUP");

    /** 过程确认环节。顺序即制作过程中的先后顺序，用于 sequence_no。 */
    public static final Map<String, String> PROCESS_CONFIRMATIONS = new LinkedHashMap<>();

    static {
        PROCESS_CONFIRMATIONS.put("CAD_DESIGN", "CAD 设计确认（制作前）");
        PROCESS_CONFIRMATIONS.put("POST_MILLING_PHOTOS", "切削/打印后照片确认");
        PROCESS_CONFIRMATIONS.put("POST_GLAZING_PHOTOS", "上釉后照片确认（质检前）");
    }

    public static final String STATUS_PLANNED = "PLANNED";
    public static final String STATUS_AWAITING_DOCTOR = "AWAITING_DOCTOR";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_REJECTED = "REJECTED";

    public static final String TRY_IN_REQUESTED = "REQUESTED";
    public static final String TRY_IN_COMPLETED = "COMPLETED";
    public static final String TRY_IN_FINALIZED = "FINALIZED";

    public static final String VARIANCE_NONE = "NONE";
    /** 医生要求的到货日早于引擎算出的可行交期——客服端的「时间异常」就是这一条。 */
    public static final String VARIANCE_EARLIER_THAN_FEASIBLE = "EARLIER_THAN_FEASIBLE";
    public static final String VARIANCE_LATER_THAN_PLAN = "LATER_THAN_PLAN";
    public static final String ALERT_WAITING_DOCTOR_CONFIRMATION = "WAITING_DOCTOR_CONFIRMATION";

    public static final String ESTIMATE_PLACEHOLDER = "PLACEHOLDER";
    public static final String ESTIMATE_CONFIRMED = "CONFIRMED";

    private OrderRuleVocabulary() {
    }

    public static String normalizeOrderType(String value) {
        return normalize(value, ORDER_TYPES, DEFAULT_ORDER_TYPE, FORM_KEY_ORDER_TYPE);
    }

    public static String normalizePriority(String value) {
        return normalize(value, PRIORITIES, DEFAULT_PRIORITY, FORM_KEY_PRIORITY);
    }

    public static String normalizeShippingMethod(String value) {
        return normalize(value, SHIPPING_METHODS, DEFAULT_SHIPPING_METHOD, FORM_KEY_SHIPPING_METHOD);
    }

    public static String normalizeConfirmationCode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!PROCESS_CONFIRMATIONS.containsKey(normalized)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "unsupported process confirmation code: " + value);
        }
        return normalized;
    }

    public static boolean requiresInboundTracking(String orderType) {
        return ORDER_TYPES_REQUIRING_INBOUND_TRACKING.contains(orderType);
    }

    private static String normalize(String value, Set<String> allowed, String fallback, String field) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "unsupported " + field + ": " + value);
        }
        return normalized;
    }
}
