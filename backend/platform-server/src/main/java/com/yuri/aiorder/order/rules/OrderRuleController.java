package com.yuri.aiorder.order.rules;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.DataResponse;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.RequirePermission;
import com.yuri.aiorder.order.rules.OrderRuleModels.AdjustDeliveryDateRequest;
import com.yuri.aiorder.order.rules.OrderRuleModels.CompleteTryInRequest;
import com.yuri.aiorder.order.rules.OrderRuleModels.DeliveryPlanResponse;
import com.yuri.aiorder.order.rules.OrderRuleModels.FinalizeTryInRequest;
import com.yuri.aiorder.order.rules.OrderRuleModels.OrderingRuleResponse;
import com.yuri.aiorder.order.rules.OrderRuleModels.ProcessConfirmationResponse;
import com.yuri.aiorder.order.rules.OrderRuleModels.RequestConfirmationRequest;
import com.yuri.aiorder.order.rules.OrderRuleModels.RespondConfirmationRequest;
import com.yuri.aiorder.order.rules.OrderRuleModels.UpdateOrderingRuleRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** TASK-034 F 批次：下单规则的读取与驱动接口。 */
@RestController
@CrossOrigin(origins = "${app.cors.allowed-origin:http://localhost:5173}")
public class OrderRuleController {

    private final OrderRuleService service;

    public OrderRuleController(OrderRuleService service) {
        this.service = service;
    }

    @GetMapping("/orders/{orderId}/delivery-plan")
    @RequirePermission(value = {"order:read-internal", "order:read-doctor"}, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<DeliveryPlanResponse> getDeliveryPlan(
            @PathVariable long orderId, BootstrapIdentity identity) {
        return new DataResponse<>(service.getDeliveryPlan(orderId, identity));
    }

    @PutMapping("/orders/{orderId}/delivery-plan/requested-date")
    @RequirePermission(value = "order:write-doctor", roles = {UserRole.DOCTOR})
    public DataResponse<DeliveryPlanResponse> adjustDeliveryDate(
            @PathVariable long orderId,
            @Valid @RequestBody AdjustDeliveryDateRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.adjustDeliveryDate(orderId, request, identity));
    }

    @PostMapping("/orders/{orderId}/process-confirmations/{confirmationCode}/request")
    @RequirePermission(value = "order:process-confirm-request", roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<List<ProcessConfirmationResponse>> requestConfirmation(
            @PathVariable long orderId,
            @PathVariable String confirmationCode,
            @Valid @RequestBody(required = false) RequestConfirmationRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.requestConfirmation(orderId, confirmationCode, identity));
    }

    @PostMapping("/orders/{orderId}/process-confirmations/{confirmationCode}/respond")
    @RequirePermission(value = "order:process-confirm-doctor", roles = {UserRole.DOCTOR})
    public DataResponse<List<ProcessConfirmationResponse>> respondConfirmation(
            @PathVariable long orderId,
            @PathVariable String confirmationCode,
            @Valid @RequestBody RespondConfirmationRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(
                service.respondConfirmation(orderId, confirmationCode, request, identity));
    }

    @PostMapping("/orders/{orderId}/try-in/complete")
    @RequirePermission(value = "order:try-in-manage", roles = {UserRole.ADMIN, UserRole.CS})
    public DataResponse<DeliveryPlanResponse> completeTryIn(
            @PathVariable long orderId,
            @Valid @RequestBody(required = false) CompleteTryInRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(
                service.completeTryIn(orderId, request == null ? null : request.note(), identity));
    }

    @PostMapping("/orders/{orderId}/try-in/finalize")
    @RequirePermission(value = "order:write-doctor", roles = {UserRole.DOCTOR})
    public DataResponse<DeliveryPlanResponse> finalizeTryIn(
            @PathVariable long orderId,
            @Valid @RequestBody(required = false) FinalizeTryInRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.finalizeTryIn(orderId, request, identity));
    }

    @GetMapping("/ordering-rules")
    @RequirePermission(value = "ordering-rule:manage", roles = {UserRole.ADMIN})
    public DataResponse<List<OrderingRuleResponse>> listRules(BootstrapIdentity identity) {
        return new DataResponse<>(service.listRules(identity));
    }

    @PutMapping("/ordering-rules/{ruleType}/{ruleKey}")
    @RequirePermission(value = "ordering-rule:manage", roles = {UserRole.ADMIN})
    public DataResponse<List<OrderingRuleResponse>> updateRule(
            @PathVariable String ruleType,
            @PathVariable String ruleKey,
            @Valid @RequestBody UpdateOrderingRuleRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.updateRule(ruleType, ruleKey, request, identity));
    }
}
