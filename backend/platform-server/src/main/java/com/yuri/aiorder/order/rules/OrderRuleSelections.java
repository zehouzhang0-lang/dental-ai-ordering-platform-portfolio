package com.yuri.aiorder.order.rules;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 从订单 {@code form_data.form_values} 中读出下单规则相关的选择。
 *
 * <p>**只有这一处解析这些字段**。此前它们散落在前端，后端一处都没读，导致医生能勾选、
 * 后端不认（GOAL-033 调研结论五）。
 *
 * <p>字段缺省时按默认值处理——F 批次之前提交的订单没有这些键，不能因此拒绝。
 * 但字段**存在且取值不认识**时直接 400：那正是「前端能选后端不认」的形态，静默当默认值处理
 * 只会把问题推到下游。
 */
public record OrderRuleSelections(
        String orderType,
        String priorityCode,
        String shippingMethod,
        String inboundTrackingNo,
        LocalDate requiredDeliveryDate,
        boolean tryInRequired,
        List<String> processConfirmationCodes) {

    public static OrderRuleSelections parse(JsonNode formValues, boolean enforceInboundTracking) {
        JsonNode values = formValues == null ? null : formValues;
        String orderType = OrderRuleVocabulary.normalizeOrderType(
                text(values, OrderRuleVocabulary.FORM_KEY_ORDER_TYPE));
        String priority = OrderRuleVocabulary.normalizePriority(
                text(values, OrderRuleVocabulary.FORM_KEY_PRIORITY));
        String shipping = OrderRuleVocabulary.normalizeShippingMethod(
                text(values, OrderRuleVocabulary.FORM_KEY_SHIPPING_METHOD));
        String trackingNo = trimToNull(text(values, OrderRuleVocabulary.FORM_KEY_INBOUND_TRACKING_NO));

        if (enforceInboundTracking
                && OrderRuleVocabulary.requiresInboundTracking(orderType)
                && trackingNo == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "inbound_tracking_no is required for order type " + orderType);
        }

        return new OrderRuleSelections(
                orderType,
                priority,
                shipping,
                trackingNo,
                parseDate(text(values, OrderRuleVocabulary.FORM_KEY_REQUIRED_DELIVERY_DATE)),
                bool(values, OrderRuleVocabulary.FORM_KEY_TRY_IN_REQUIRED),
                confirmationCodes(values));
    }

    private static List<String> confirmationCodes(JsonNode values) {
        JsonNode node = values == null ? null : values.get(OrderRuleVocabulary.FORM_KEY_PROCESS_REVIEWS);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode entry : node) {
            if (entry == null || !entry.isTextual() || entry.asText().isBlank()) {
                continue;
            }
            codes.add(OrderRuleVocabulary.normalizeConfirmationCode(entry.asText()));
        }
        // 按词汇表定义的制作顺序排列，而不是按医生勾选顺序——sequence_no 表示环节先后。
        List<String> ordered = new ArrayList<>();
        for (String code : OrderRuleVocabulary.PROCESS_CONFIRMATIONS.keySet()) {
            if (codes.contains(code)) {
                ordered.add(code);
            }
        }
        return List.copyOf(ordered);
    }

    private static String text(JsonNode values, String key) {
        if (values == null) {
            return null;
        }
        JsonNode node = values.get(key);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.asText(null);
    }

    private static boolean bool(JsonNode values, String key) {
        if (values == null) {
            return false;
        }
        JsonNode node = values.get(key);
        if (node == null || node.isNull()) {
            return false;
        }
        return node.isBoolean() ? node.booleanValue() : Boolean.parseBoolean(node.asText(""));
    }

    private static LocalDate parseDate(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    OrderRuleVocabulary.FORM_KEY_REQUIRED_DELIVERY_DATE + " must be an ISO date", ex);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
