package com.yuri.aiorder.workflow.standardtime;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.DataResponse;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.RequirePermission;
import com.yuri.aiorder.workflow.standardtime.WorkflowStandardTimeModels.BulkUpdateRequest;
import com.yuri.aiorder.workflow.standardtime.WorkflowStandardTimeModels.CopyVersionRequest;
import com.yuri.aiorder.workflow.standardtime.WorkflowStandardTimeModels.NodeTimeResponse;
import com.yuri.aiorder.workflow.standardtime.WorkflowStandardTimeModels.PublishRequest;
import com.yuri.aiorder.workflow.standardtime.WorkflowStandardTimeModels.VersionResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "${app.cors.allowed-origin:http://localhost:5173}")
public class WorkflowStandardTimeController {

    private final WorkflowStandardTimeService service;

    public WorkflowStandardTimeController(WorkflowStandardTimeService service) {
        this.service = service;
    }

    @GetMapping("/admin/workflow/standard-times/versions")
    @RequirePermission(value = "workflow:standard-time:manage", roles = {UserRole.ADMIN})
    public DataResponse<List<VersionResponse>> listVersions(BootstrapIdentity identity) {
        return new DataResponse<>(service.listVersions(identity));
    }

    @PostMapping("/admin/workflow/standard-times/versions")
    @RequirePermission(value = "workflow:standard-time:manage", roles = {UserRole.ADMIN})
    public DataResponse<VersionResponse> copyVersion(
            @Valid @RequestBody CopyVersionRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.copyVersion(request, identity));
    }

    @GetMapping("/admin/workflow/standard-times/versions/{versionId}/nodes")
    @RequirePermission(value = "workflow:standard-time:manage", roles = {UserRole.ADMIN})
    public DataResponse<List<NodeTimeResponse>> listNodes(
            @PathVariable long versionId,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.listNodes(versionId, identity));
    }

    @PutMapping("/admin/workflow/standard-times/versions/{versionId}/nodes")
    @RequirePermission(value = "workflow:standard-time:manage", roles = {UserRole.ADMIN})
    public DataResponse<List<NodeTimeResponse>> bulkUpdate(
            @PathVariable long versionId,
            @Valid @RequestBody BulkUpdateRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.bulkUpdate(versionId, request, identity));
    }

    @PostMapping("/admin/workflow/standard-times/versions/{versionId}/publish")
    @RequirePermission(value = "workflow:standard-time:manage", roles = {UserRole.ADMIN})
    public DataResponse<VersionResponse> publish(
            @PathVariable long versionId,
            @Valid @RequestBody PublishRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.publish(versionId, request, identity));
    }
}
