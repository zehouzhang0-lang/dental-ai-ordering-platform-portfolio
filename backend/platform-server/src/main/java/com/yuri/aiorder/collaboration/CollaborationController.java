package com.yuri.aiorder.collaboration;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.DataResponse;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.RequirePermission;
import com.yuri.aiorder.design.DesignTaskService;
import java.util.List;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "${app.cors.allowed-origin:http://localhost:5173}")
public class CollaborationController {

    private final CollaborationService collaborationService;
    private final DesignTaskService designTaskService;

    public CollaborationController(
            CollaborationService collaborationService,
            DesignTaskService designTaskService) {
        this.collaborationService = collaborationService;
        this.designTaskService = designTaskService;
    }

    @GetMapping("/orders/{orderId}/messages")
    @RequirePermission(value = {"message:manage", "message:operate-production", "order:read-doctor"}, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<List<MessageResponse>> listMessages(
            @PathVariable long orderId,
            BootstrapIdentity identity) {
        return new DataResponse<>(collaborationService.listMessages(orderId, identity));
    }

    @PostMapping("/orders/{orderId}/messages")
    @RequirePermission(value = {"message:manage", "message:operate-production", "order:read-doctor"}, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<MessageResponse> sendMessage(
            @PathVariable long orderId,
            @RequestBody MessageRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(collaborationService.sendMessage(orderId, request, identity));
    }

    @GetMapping("/orders/{orderId}/message-mentionable-users")
    @RequirePermission(value = {"message:manage", "message:operate-production", "order:read-doctor"}, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<List<MentionableUserResponse>> listMentionableUsers(
            @PathVariable long orderId,
            BootstrapIdentity identity) {
        return new DataResponse<>(collaborationService.listMentionableUsers(orderId, identity));
    }

    @GetMapping("/messages/attention-items")
    @RequirePermission(value = {"message:manage", "message:operate-production", "order:read-doctor"}, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<List<MessageAttentionItemResponse>> listAttentionItems(BootstrapIdentity identity) {
        return new DataResponse<>(collaborationService.listAttentionItems(identity));
    }

    @PostMapping("/messages/attention-items/{messageId}/resolve")
    @RequirePermission(value = {"message:manage", "message:operate-production", "order:read-doctor"}, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<MessageAttentionItemResponse> resolveAttentionItem(
            @PathVariable long messageId,
            BootstrapIdentity identity) {
        return new DataResponse<>(collaborationService.resolveAttentionItem(messageId, identity));
    }

    @PostMapping("/messages/{msgId}/review")
    @RequirePermission(value = "message:manage", roles = {UserRole.ADMIN, UserRole.CS})
    public DataResponse<MessageResponse> reviewMessage(
            @PathVariable long msgId,
            @RequestBody MessageReviewRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(collaborationService.reviewMessage(msgId, request, identity));
    }

    @GetMapping("/messages/pending-review")
    @RequirePermission(value = "message:manage", roles = {UserRole.ADMIN, UserRole.CS})
    public DataResponse<List<MessageResponse>> pendingMessages(
            BootstrapIdentity identity) {
        return new DataResponse<>(collaborationService.pendingMessages(identity));
    }

    @GetMapping("/orders/{orderId}/design-drafts")
    @RequirePermission(value = {
            "design-task:operate-self",
            "design-draft:internal-review",
            "design-task:manage",
            "design-task:read-progress",
            "order:read-doctor"
    }, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<List<DesignDraftResponse>> listDesignDrafts(
            @PathVariable long orderId,
            BootstrapIdentity identity) {
        return new DataResponse<>(designTaskService.listDrafts(orderId, identity));
    }

    @PostMapping("/orders/{orderId}/design-drafts")
    @RequirePermission(value = "design-task:operate-self", roles = UserRole.WORKER)
    public DataResponse<DesignDraftResponse> uploadDesignDraft(
            @PathVariable long orderId,
            @RequestBody DesignDraftRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(designTaskService.uploadDraft(orderId, request, identity));
    }

    @PostMapping("/orders/{orderId}/design-drafts/{draftId}/cs-review")
    @RequirePermission("design-draft:internal-review")
    public DataResponse<DesignDraftResponse> reviewDesignDraft(
            @PathVariable long orderId,
            @PathVariable long draftId,
            @RequestBody DesignDraftReviewRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(designTaskService.internalReview(orderId, draftId, request, identity));
    }

    @PostMapping("/orders/{orderId}/design-drafts/{draftId}/doctor-confirm")
    @RequirePermission(value = "order:write-doctor", roles = UserRole.DOCTOR)
    public DataResponse<DesignDraftResponse> doctorConfirmDesignDraft(
            @PathVariable long orderId,
            @PathVariable long draftId,
            @RequestBody DoctorDraftConfirmRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(designTaskService.doctorConfirm(orderId, draftId, request, identity));
    }

    @GetMapping("/orders/{orderId}/bill")
    @RequirePermission(value = {"message:manage", "order:read-doctor"}, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.DOCTOR})
    public DataResponse<BillResponse> getBill(
            @PathVariable long orderId,
            BootstrapIdentity identity) {
        return new DataResponse<>(collaborationService.getBill(orderId, identity));
    }

    @PostMapping("/orders/{orderId}/bill")
    @RequirePermission(value = "message:manage", roles = {UserRole.ADMIN, UserRole.CS})
    public DataResponse<BillResponse> uploadBill(
            @PathVariable long orderId,
            @RequestBody BillRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(collaborationService.uploadBill(orderId, request, identity));
    }

    @PostMapping("/orders/{orderId}/bill/payment-status")
    @RequirePermission(value = "message:manage", roles = {UserRole.ADMIN, UserRole.CS})
    public DataResponse<BillResponse> updatePaymentStatus(
            @PathVariable long orderId,
            @RequestBody PaymentStatusRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(collaborationService.updatePaymentStatus(orderId, request, identity));
    }

    @GetMapping("/orders/{orderId}/payments")
    @RequirePermission(value = {"message:manage", "order:read-doctor"}, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.DOCTOR})
    public DataResponse<List<PaymentRecordResponse>> listPaymentRecords(
            @PathVariable long orderId,
            BootstrapIdentity identity) {
        return new DataResponse<>(collaborationService.listPaymentRecords(orderId, identity));
    }

    @PostMapping("/orders/{orderId}/payments")
    @RequirePermission(value = "message:manage", roles = {UserRole.ADMIN, UserRole.CS})
    public DataResponse<PaymentRecordResponse> createPaymentRecord(
            @PathVariable long orderId,
            @RequestBody PaymentRecordRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(collaborationService.createPaymentRecord(orderId, request, identity));
    }

    @GetMapping("/orders/{orderId}/logistics")
    @RequirePermission(value = {"message:manage", "order:read-doctor"}, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.DOCTOR})
    public DataResponse<LogisticsResponse> getLogistics(
            @PathVariable long orderId,
            BootstrapIdentity identity) {
        return new DataResponse<>(collaborationService.getLogistics(orderId, identity));
    }

    @PostMapping("/orders/{orderId}/logistics")
    @RequirePermission(value = "message:manage", roles = {UserRole.ADMIN, UserRole.CS})
    public DataResponse<LogisticsResponse> shipOrder(
            @PathVariable long orderId,
            @RequestBody LogisticsRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(collaborationService.shipOrder(orderId, request, identity));
    }

    @GetMapping("/logistics/orders")
    @RequirePermission(value = "message:manage", roles = {UserRole.ADMIN, UserRole.CS})
    public DataResponse<List<DeliveryOrderResponse>> listDeliveryOrders(
            @RequestParam(name = "logistics_status", required = false) String logisticsStatus,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            BootstrapIdentity identity) {
        return new DataResponse<>(collaborationService.listDeliveryOrders(logisticsStatus, limit, identity));
    }

    @PostMapping("/orders/{orderId}/logistics/exception")
    @RequirePermission(value = "message:manage", roles = {UserRole.ADMIN, UserRole.CS})
    public DataResponse<DeliveryOrderResponse> updateLogisticsException(
            @PathVariable long orderId,
            @RequestBody LogisticsExceptionRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(collaborationService.updateLogisticsException(orderId, request, identity));
    }
}
