package com.yuri.aiorder.order.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** TASK-034 F 批次的请求与响应模型。 */
public final class OrderRuleModels {

    private OrderRuleModels() {
    }

    /**
     * 交期计划。
     *
     * <p>{@code estimate_status = PLACEHOLDER} 表示本次计算用到了客户尚未确认的占位周期，
     * 界面必须据此标注「待确认」；{@code placeholder_rules} 列出具体是哪几条，便于客服解释。
     */
    public record DeliveryPlanResponse(
            @JsonProperty("order_id") long orderId,
            @JsonProperty("order_no") String orderNo,
            @JsonProperty("order_type") String orderType,
            @JsonProperty("priority_code") String priorityCode,
            @JsonProperty("shipping_method") String shippingMethod,
            @JsonProperty("inbound_tracking_no") String inboundTrackingNo,
            @JsonProperty("baseline_date") LocalDate baselineDate,
            @JsonProperty("base_cycle_days") int baseCycleDays,
            @JsonProperty("priority_cap_days") int priorityCapDays,
            @JsonProperty("process_confirmation_count") int processConfirmationCount,
            @JsonProperty("process_confirmation_days") int processConfirmationDays,
            @JsonProperty("waiting_days") int waitingDays,
            @JsonProperty("production_days") int productionDays,
            @JsonProperty("transit_days") int transitDays,
            @JsonProperty("computed_delivery_date") LocalDate computedDeliveryDate,
            @JsonProperty("doctor_requested_delivery_date") LocalDate doctorRequestedDeliveryDate,
            @JsonProperty("variance_days") Integer varianceDays,
            @JsonProperty("variance_flag") String varianceFlag,
            @JsonProperty("delivery_alert") String deliveryAlert,
            @JsonProperty("delivery_alert_message") String deliveryAlertMessage,
            @JsonProperty("estimate_status") String estimateStatus,
            @JsonProperty("placeholder_rules") List<String> placeholderRules,
            @JsonProperty("process_confirmations") List<ProcessConfirmationResponse> processConfirmations,
            @JsonProperty("try_in") TryInResponse tryIn,
            @JsonProperty("bill_items") List<BillItemResponse> billItems) {
    }

    public record ProcessConfirmationResponse(
            @JsonProperty("confirmation_id") long confirmationId,
            @JsonProperty("order_id") long orderId,
            @JsonProperty("confirmation_code") String confirmationCode,
            @JsonProperty("confirmation_name") String confirmationName,
            @JsonProperty("sequence_no") int sequenceNo,
            @JsonProperty("confirmation_status") String confirmationStatus,
            @JsonProperty("requested_at") LocalDateTime requestedAt,
            @JsonProperty("responded_at") LocalDateTime respondedAt,
            @JsonProperty("doctor_comment") String doctorComment,
            @JsonProperty("waiting_days") int waitingDays,
            @JsonProperty("overdue") boolean overdue) {
    }

    public record TryInResponse(
            @JsonProperty("order_id") long orderId,
            @JsonProperty("try_in_required") boolean tryInRequired,
            @JsonProperty("try_in_status") String tryInStatus,
            @JsonProperty("completed_at") LocalDateTime completedAt,
            @JsonProperty("finalized_at") LocalDateTime finalizedAt,
            @JsonProperty("finalize_note") String finalizeNote,
            @JsonProperty("can_select_final_product") boolean canSelectFinalProduct) {
    }

    public record BillItemResponse(
            @JsonProperty("bill_item_id") long billItemId,
            @JsonProperty("order_id") long orderId,
            @JsonProperty("item_code") String itemCode,
            @JsonProperty("item_name") String itemName,
            @JsonProperty("quantity") int quantity,
            @JsonProperty("pricing_status") String pricingStatus,
            @JsonProperty("amount_cents") Long amountCents,
            @JsonProperty("currency") String currency,
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("remark") String remark) {
    }

    public record RequestConfirmationRequest(
            @JsonProperty("remark") @Size(max = 512) String remark) {
    }

    public record RespondConfirmationRequest(
            @JsonProperty("accepted") @NotNull Boolean accepted,
            @JsonProperty("comment") @Size(max = 512) String comment) {
    }

    public record CompleteTryInRequest(
            @JsonProperty("note") @Size(max = 512) String note) {
    }

    /**
     * 试戴完成后在**同一订单**上继续选择成品与材料。不新建订单，因此没有 order 相关字段。
     */
    public record FinalizeTryInRequest(
            @JsonProperty("product_id") Long productId,
            @JsonProperty("variant_id") Long variantId,
            @JsonProperty("material_selections") List<MaterialSelection> materialSelections,
            @JsonProperty("note") @Size(max = 512) String note) {
    }

    public record MaterialSelection(
            @JsonProperty("item_id") Long itemId,
            @JsonProperty("quantity") Integer quantity) {
    }

    public record AdjustDeliveryDateRequest(
            @JsonProperty("requested_delivery_date") @NotNull LocalDate requestedDeliveryDate,
            @JsonProperty("reason") @Size(max = 255) String reason) {
    }

    public record OrderingRuleResponse(
            @JsonProperty("rule_type") String ruleType,
            @JsonProperty("rule_key") String ruleKey,
            @JsonProperty("numeric_value") int numericValue,
            @JsonProperty("confirmation_status") String confirmationStatus,
            @JsonProperty("display_name") String displayName) {
    }

    public record UpdateOrderingRuleRequest(
            @JsonProperty("numeric_value") @NotNull Integer numericValue,
            @JsonProperty("confirmation_status") String confirmationStatus) {
    }
}
