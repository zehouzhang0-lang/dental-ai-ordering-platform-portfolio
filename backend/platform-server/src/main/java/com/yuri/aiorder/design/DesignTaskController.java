package com.yuri.aiorder.design;

import com.yuri.aiorder.collaboration.DesignDraftResponse;
import com.yuri.aiorder.collaboration.DesignDraftReviewRequest;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.DataResponse;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.RequirePermission;
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
public class DesignTaskController {

    private final DesignTaskService designTaskService;

    public DesignTaskController(DesignTaskService designTaskService) {
        this.designTaskService = designTaskService;
    }

    @GetMapping("/design-tasks/pool")
    @RequirePermission(value = {"design-task:claim", "design-task:manage"}, roles = {
            UserRole.WORKER, UserRole.ADMIN})
    public DataResponse<List<DesignTaskResponse>> pool(
            @RequestParam(name = "product_type", required = false) String productType,
            BootstrapIdentity identity) {
        return new DataResponse<>(designTaskService.listPool(productType, identity));
    }

    @GetMapping("/design-tasks/mine")
    @RequirePermission(value = {"design-task:operate-self", "design-task:manage"}, roles = {
            UserRole.WORKER, UserRole.ADMIN})
    public DataResponse<List<DesignTaskResponse>> mine(
            @RequestParam(required = false) String status,
            BootstrapIdentity identity) {
        return new DataResponse<>(designTaskService.listMine(status, identity));
    }

    @GetMapping("/design-tasks/internal-review-queue")
    @RequirePermission("design-draft:internal-review")
    public DataResponse<List<DesignTaskResponse>> internalReviewQueue(BootstrapIdentity identity) {
        return new DataResponse<>(designTaskService.listInternalReviewQueue(identity));
    }

    @GetMapping("/design-tasks/manage")
    @RequirePermission(value = "design-task:manage", roles = UserRole.ADMIN)
    public DataResponse<List<DesignTaskResponse>> manage(BootstrapIdentity identity) {
        return new DataResponse<>(designTaskService.listManaged(identity));
    }

    @GetMapping("/orders/{orderId}/design-task")
    @RequirePermission(value = {
            "design-task:operate-self",
            "design-draft:internal-review",
            "design-task:manage",
            "design-task:read-progress",
            "order:read-doctor"
    }, roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<DesignTaskResponse> getByOrder(
            @PathVariable long orderId,
            BootstrapIdentity identity) {
        return new DataResponse<>(designTaskService.getByOrder(orderId, identity));
    }

    @PostMapping("/design-tasks/{taskId}/claim")
    @RequirePermission(value = "design-task:claim", roles = UserRole.WORKER)
    public DataResponse<DesignTaskResponse> claim(
            @PathVariable long taskId,
            BootstrapIdentity identity) {
        return new DataResponse<>(designTaskService.claim(taskId, identity));
    }

    @PostMapping("/design-tasks/{taskId}/transfer")
    @RequirePermission(value = "design-task:manage", roles = UserRole.ADMIN)
    public DataResponse<DesignTaskResponse> transfer(
            @PathVariable long taskId,
            @RequestBody DesignTaskTransferRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(designTaskService.transfer(taskId, request, identity));
    }

    @PostMapping("/orders/{orderId}/design-drafts/{draftId}/submit")
    @RequirePermission(value = "design-task:operate-self", roles = UserRole.WORKER)
    public DataResponse<DesignDraftResponse> submitDraft(
            @PathVariable long orderId,
            @PathVariable long draftId,
            BootstrapIdentity identity) {
        return new DataResponse<>(designTaskService.submitDraft(orderId, draftId, identity));
    }

    @PostMapping("/orders/{orderId}/design-drafts/{draftId}/internal-review")
    @RequirePermission("design-draft:internal-review")
    public DataResponse<DesignDraftResponse> internalReview(
            @PathVariable long orderId,
            @PathVariable long draftId,
            @RequestBody DesignDraftReviewRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(designTaskService.internalReview(orderId, draftId, request, identity));
    }

}
