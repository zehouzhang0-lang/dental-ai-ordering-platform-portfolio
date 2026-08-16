package com.yuri.aiorder.order.rules;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.auth.AccessControlService;
import com.yuri.aiorder.order.rules.DeliveryPlanService.Computation;
import com.yuri.aiorder.order.rules.DeliveryPlanService.ConfirmationRow;
import com.yuri.aiorder.order.rules.DeliveryPlanService.PlanRow;
import com.yuri.aiorder.order.rules.OrderRuleModels.AdjustDeliveryDateRequest;
import com.yuri.aiorder.order.rules.OrderRuleModels.DeliveryPlanResponse;
import com.yuri.aiorder.order.rules.OrderRuleModels.FinalizeTryInRequest;
import com.yuri.aiorder.order.rules.OrderRuleModels.OrderingRuleResponse;
import com.yuri.aiorder.order.rules.OrderRuleModels.ProcessConfirmationResponse;
import com.yuri.aiorder.order.rules.OrderRuleModels.RespondConfirmationRequest;
import com.yuri.aiorder.order.rules.OrderRuleModels.UpdateOrderingRuleRequest;
import com.yuri.aiorder.order.rules.OrderRuleSupport.OrderRow;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 下单规则的对外入口：提交时建立规则后果，之后按规则驱动过程确认、试戴与交期。
 *
 * <p>拆分理由见各协作者的类注释；这里只负责编排与授权，不含计算逻辑。
 */
@Service
public class OrderRuleService {

    private final JdbcClient jdbcClient;
    private final OrderRuleSupport support;
    private final AccessControlService accessControlService;
    private final DeliveryPlanService deliveryPlanService;
    private final TryInService tryInService;
    private final OrderBillItemService billItemService;
    private final OrderingRuleCatalog ruleCatalog;

    public OrderRuleService(
            JdbcClient jdbcClient,
            OrderRuleSupport support,
            AccessControlService accessControlService,
            DeliveryPlanService deliveryPlanService,
            TryInService tryInService,
            OrderBillItemService billItemService,
            OrderingRuleCatalog ruleCatalog) {
        this.jdbcClient = jdbcClient;
        this.support = support;
        this.accessControlService = accessControlService;
        this.deliveryPlanService = deliveryPlanService;
        this.tryInService = tryInService;
        this.billItemService = billItemService;
        this.ruleCatalog = ruleCatalog;
    }

    /** 病例组提交时调用。同一批提交内任一子订单失败都会整体回滚。 */
    @Transactional
    public void initializeOnSubmit(
            long orderId,
            String workflowProductType,
            String productName,
            OrderRuleSelections selections,
            LocalDate submittedOn) {
        deliveryPlanService.initialize(orderId, workflowProductType, selections, submittedOn);
        tryInService.initialize(orderId, selections.tryInRequired());
        billItemService.generateProductItem(orderId, productName);
        deliveryPlanService.recompute(orderId, workflowProductType);
    }

    @Transactional
    public DeliveryPlanResponse getDeliveryPlan(long orderId, BootstrapIdentity identity) {
        OrderRow order = support.loadScopedOrder(orderId, identity, "identity cannot access this order");
        support.requireDoctorOwnership(order, identity);
        return assemble(order);
    }

    /**
     * 医生调整到货时间。调整后交期差异落库，客服端的订单视图据此出现「时间异常」提示，
     * 并给受理客服推一条通知——只落库不通知等于没提示。
     */
    @Transactional
    public DeliveryPlanResponse adjustDeliveryDate(
            long orderId, AdjustDeliveryDateRequest request, BootstrapIdentity identity) {
        OrderRow order = support.loadScopedOrder(orderId, identity, "identity cannot access this order");
        support.requireDoctorOwnership(order, identity);
        Computation computation =
                deliveryPlanService.adjustRequestedDeliveryDate(orderId, request.requestedDeliveryDate());
        if (computation != null
                && OrderRuleVocabulary.VARIANCE_EARLIER_THAN_FEASIBLE.equals(
                        computation.plan().varianceFlag())) {
            support.emit(
                    order,
                    "DELIVERY_DATE_VARIANCE",
                    "CS",
                    order.csUserId(),
                    "医生要求的到货时间早于系统可行交期 "
                            + Math.abs(computation.plan().varianceDays()) + " 天，请与医生确认。");
        }
        return assemble(order);
    }

    @Transactional
    public List<ProcessConfirmationResponse> requestConfirmation(
            long orderId, String confirmationCode, BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity,
                "order:process-confirm-request",
                "requesting a process confirmation requires order:process-confirm-request");
        OrderRow order = support.loadScopedOrder(orderId, identity, "identity cannot access this order");
        String code = OrderRuleVocabulary.normalizeConfirmationCode(confirmationCode);
        ConfirmationRow confirmation =
                deliveryPlanService.requestConfirmation(orderId, code, identity.userId());
        deliveryPlanService.recompute(orderId, order.productType());
        support.emit(
                order,
                "PROCESS_CONFIRMATION_REQUESTED",
                "DOCTOR",
                order.doctorUserId(),
                "「" + confirmation.confirmationName() + "」等待您确认，逾期未确认会顺延交期。");
        return deliveryPlanService.listConfirmations(orderId);
    }

    @Transactional
    public List<ProcessConfirmationResponse> respondConfirmation(
            long orderId,
            String confirmationCode,
            RespondConfirmationRequest request,
            BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity,
                "order:process-confirm-doctor",
                "confirming a production step requires order:process-confirm-doctor");
        OrderRow order = support.loadScopedOrder(orderId, identity, "identity cannot access this order");
        support.requireDoctorOwnership(order, identity);
        String code = OrderRuleVocabulary.normalizeConfirmationCode(confirmationCode);
        ConfirmationRow confirmation = deliveryPlanService.respondConfirmation(
                orderId, code, Boolean.TRUE.equals(request.accepted()), request.comment(), identity.userId());
        deliveryPlanService.recompute(orderId, order.productType());
        support.emit(
                order,
                Boolean.TRUE.equals(request.accepted())
                        ? "PROCESS_CONFIRMATION_ACCEPTED"
                        : "PROCESS_CONFIRMATION_REJECTED",
                "CS",
                order.csUserId(),
                "医生已回复「" + confirmation.confirmationName() + "」。");
        return deliveryPlanService.listConfirmations(orderId);
    }

    @Transactional
    public DeliveryPlanResponse completeTryIn(long orderId, String note, BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, "order:try-in-manage", "recording try-in completion requires order:try-in-manage");
        OrderRow order = support.loadScopedOrder(orderId, identity, "identity cannot access this order");
        tryInService.complete(orderId, note, identity.userId());
        support.emit(
                order,
                "TRY_IN_COMPLETED",
                "DOCTOR",
                order.doctorUserId(),
                "试戴已完成，请在原订单中继续选择成品与材料。");
        return assemble(order);
    }

    @Transactional
    public DeliveryPlanResponse finalizeTryIn(
            long orderId, FinalizeTryInRequest request, BootstrapIdentity identity) {
        accessControlService.requireDoctorPortalAction(
                identity, "order:write-doctor", "only doctors can select the final product");
        OrderRow order = support.loadScopedOrder(orderId, identity, "identity cannot access this order");
        support.requireDoctorOwnership(order, identity);
        tryInService.finalizeSelection(orderId, request, identity.userId());
        support.emit(
                order,
                "TRY_IN_FINALIZED",
                "CS",
                order.csUserId(),
                "医生已在试戴订单上选定成品，请核价。");
        return assemble(support.loadOrder(orderId));
    }

    public List<OrderingRuleResponse> listRules(BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, "ordering-rule:manage", "ordering rules require ordering-rule:manage");
        return ruleCatalog.listAll().stream()
                .map(rule -> new OrderingRuleResponse(
                        rule.ruleType(),
                        rule.ruleKey(),
                        rule.days(),
                        rule.confirmationStatus(),
                        rule.displayName()))
                .toList();
    }

    /**
     * 占位值转正的入口：客户给出真实标准周期后在管理端改这里，不需要改代码重新发版。
     */
    @Transactional
    public List<OrderingRuleResponse> updateRule(
            String ruleType, String ruleKey, UpdateOrderingRuleRequest request, BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, "ordering-rule:manage", "ordering rules require ordering-rule:manage");
        String status = request.confirmationStatus() == null || request.confirmationStatus().isBlank()
                ? OrderRuleVocabulary.ESTIMATE_CONFIRMED
                : request.confirmationStatus().trim().toUpperCase(Locale.ROOT);
        if (!OrderRuleVocabulary.ESTIMATE_CONFIRMED.equals(status)
                && !OrderRuleVocabulary.ESTIMATE_PLACEHOLDER.equals(status)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "confirmation_status must be CONFIRMED or PLACEHOLDER");
        }
        int updated = jdbcClient.sql("""
                        UPDATE ordering_rule_config
                        SET numeric_value = :numericValue,
                            confirmation_status = :confirmationStatus,
                            updated_by_user_id = :operatorUserId
                        WHERE rule_type = :ruleType
                          AND rule_key = :ruleKey
                        """)
                .param("numericValue", request.numericValue())
                .param("confirmationStatus", status)
                .param("operatorUserId", identity.userId())
                .param("ruleType", ruleType.trim().toUpperCase(Locale.ROOT))
                .param("ruleKey", ruleKey.trim().toUpperCase(Locale.ROOT))
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ordering rule not found");
        }
        return listRules(identity);
    }

    private DeliveryPlanResponse assemble(OrderRow order) {
        Computation computation = deliveryPlanService.recompute(order.orderId(), order.productType());
        if (computation == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "order has no delivery plan yet; it is created when the case group is submitted");
        }
        PlanRow plan = computation.plan();
        List<ProcessConfirmationResponse> confirmations =
                deliveryPlanService.listConfirmations(order.orderId());
        String alert = resolveAlert(plan.varianceFlag(), computation.waitingAlert());
        return new DeliveryPlanResponse(
                order.orderId(),
                order.orderNo(),
                plan.orderType(),
                plan.priorityCode(),
                plan.shippingMethod(),
                plan.inboundTrackingNo(),
                plan.baselineDate(),
                plan.baseCycleDays(),
                plan.priorityCapDays(),
                plan.processConfirmationCount(),
                plan.processConfirmationDays(),
                plan.waitingDays(),
                plan.productionDays(),
                plan.transitDays(),
                plan.computedDeliveryDate(),
                plan.doctorRequestedDeliveryDate(),
                plan.varianceDays(),
                plan.varianceFlag(),
                alert,
                alertMessage(alert, plan),
                plan.estimateStatus(),
                computation.placeholderRules(),
                confirmations,
                tryInService.toResponse(order.orderId()),
                billItemService.list(order.orderId()));
    }

    private String resolveAlert(String varianceFlag, boolean waitingAlert) {
        if (OrderRuleVocabulary.VARIANCE_EARLIER_THAN_FEASIBLE.equals(varianceFlag)) {
            return OrderRuleVocabulary.VARIANCE_EARLIER_THAN_FEASIBLE;
        }
        if (waitingAlert) {
            return OrderRuleVocabulary.ALERT_WAITING_DOCTOR_CONFIRMATION;
        }
        if (OrderRuleVocabulary.VARIANCE_LATER_THAN_PLAN.equals(varianceFlag)) {
            return OrderRuleVocabulary.VARIANCE_LATER_THAN_PLAN;
        }
        return null;
    }

    private String alertMessage(String alert, PlanRow plan) {
        if (alert == null) {
            return null;
        }
        return switch (alert) {
            case OrderRuleVocabulary.VARIANCE_EARLIER_THAN_FEASIBLE -> "医生要求到货 "
                    + plan.doctorRequestedDeliveryDate() + "，早于系统可行交期 "
                    + plan.computedDeliveryDate() + "（相差 "
                    + Math.abs(plan.varianceDays() == null ? 0 : plan.varianceDays()) + " 天），请与医生确认。";
            case OrderRuleVocabulary.ALERT_WAITING_DOCTOR_CONFIRMATION -> "有过程确认环节超过宽限期未获医生确认，"
                    + "订单处于等待状态，交期已顺延 " + plan.waitingDays() + " 天。";
            case OrderRuleVocabulary.VARIANCE_LATER_THAN_PLAN -> "医生要求到货 "
                    + plan.doctorRequestedDeliveryDate() + "，晚于系统可行交期 "
                    + plan.computedDeliveryDate() + "，按医生要求安排。";
            default -> null;
        };
    }
}
