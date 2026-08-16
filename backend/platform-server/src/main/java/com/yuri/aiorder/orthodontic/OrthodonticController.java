package com.yuri.aiorder.orthodontic;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.DataResponse;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.RequirePermission;
import com.yuri.aiorder.orthodontic.OrthodonticModels.CreateChangeRequest;
import com.yuri.aiorder.orthodontic.OrthodonticModels.CreatePlanVersionRequest;
import com.yuri.aiorder.orthodontic.OrthodonticModels.CreateProductionBatchRequest;
import com.yuri.aiorder.orthodontic.OrthodonticModels.ReviewPlanRequest;
import com.yuri.aiorder.orthodontic.OrthodonticModels.SavePrescriptionRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "${app.cors.allowed-origin:http://localhost:5173}")
public class OrthodonticController {

    private final OrthodonticService service;

    public OrthodonticController(OrthodonticService service) {
        this.service = service;
    }

    @GetMapping("/orders/{orderId}/orthodontic-case")
    @RequirePermission(value = {
            "order:read-doctor",
            "order:read-internal",
            "workflow:orthodontic-case:read"
    }, roles = {UserRole.DOCTOR, UserRole.CS, UserRole.WORKER, UserRole.ADMIN})
    public DataResponse<Map<String, Object>> get(
            @PathVariable long orderId,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.get(orderId, identity));
    }

    @PutMapping("/orders/{orderId}/orthodontic-prescription")
    @RequirePermission(value = "order:write-doctor", roles = UserRole.DOCTOR)
    public DataResponse<Map<String, Object>> savePrescription(
            @PathVariable long orderId,
            @Valid @RequestBody SavePrescriptionRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.savePrescription(orderId, request, identity));
    }

    @PostMapping("/orders/{orderId}/orthodontic-plan-versions")
    @RequirePermission(value = {"design-task:operate-self", "design-task:manage"}, roles = {
            UserRole.WORKER, UserRole.ADMIN})
    public DataResponse<Map<String, Object>> createPlanVersion(
            @PathVariable long orderId,
            @Valid @RequestBody CreatePlanVersionRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.createPlanVersion(orderId, request, identity));
    }

    @PostMapping("/orthodontic-plan-versions/{planVersionId}/internal-review")
    @RequirePermission("design-draft:internal-review")
    public DataResponse<Map<String, Object>> internalReview(
            @PathVariable long planVersionId,
            @Valid @RequestBody ReviewPlanRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.reviewInternal(planVersionId, request, identity));
    }

    @PostMapping("/orthodontic-plan-versions/{planVersionId}/doctor-review")
    @RequirePermission(value = "order:write-doctor", roles = UserRole.DOCTOR)
    public DataResponse<Map<String, Object>> doctorReview(
            @PathVariable long planVersionId,
            @Valid @RequestBody ReviewPlanRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.reviewDoctor(planVersionId, request, identity));
    }

    @PostMapping("/orders/{orderId}/orthodontic-production-batches")
    @RequirePermission(value = "workflow:orthodontic-batch:manage", roles = UserRole.ADMIN)
    public DataResponse<Map<String, Object>> createBatch(
            @PathVariable long orderId,
            @Valid @RequestBody CreateProductionBatchRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.createBatch(orderId, request, identity));
    }

    @PostMapping("/orders/{orderId}/orthodontic-change-requests")
    @RequirePermission(value = {
            "order:write-doctor",
            "order:read-internal",
            "workflow:orthodontic-case:read"
    }, roles = {UserRole.DOCTOR, UserRole.CS, UserRole.ADMIN})
    public DataResponse<Map<String, Object>> createChangeRequest(
            @PathVariable long orderId,
            @Valid @RequestBody CreateChangeRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.createChangeRequest(orderId, request, identity));
    }
}
