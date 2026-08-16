package com.yuri.aiorder.export;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.DataResponse;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.RequirePermission;
import com.yuri.aiorder.export.ExportModels.ApproveExportRequest;
import com.yuri.aiorder.export.ExportModels.CreateExportRequest;
import com.yuri.aiorder.export.ExportModels.DatasetResponse;
import com.yuri.aiorder.export.ExportModels.ExportAuditResponse;
import com.yuri.aiorder.export.ExportModels.ExportRequestResponse;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * TASK-034 E 批次：导出管控。
 *
 * <p>注意所有接口的 {@code roles} 里**没有 DOCTOR**——客户明确要求「不允许医生直接导出」。
 * 医生端的角色也没有任何导出权限码，两层都挡住。
 */
@RestController
@CrossOrigin(origins = "${app.cors.allowed-origin:http://localhost:5173}")
public class ExportController {

    private final ExportService service;

    public ExportController(ExportService service) {
        this.service = service;
    }

    @GetMapping("/exports/datasets")
    @RequirePermission(value = "export:execute", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<List<DatasetResponse>> listDatasets(BootstrapIdentity identity) {
        return new DataResponse<>(service.listDatasets(identity));
    }

    @GetMapping("/exports")
    @RequirePermission(value = "export:execute", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<List<ExportRequestResponse>> listRequests(BootstrapIdentity identity) {
        return new DataResponse<>(service.listRequests(identity));
    }

    @PostMapping("/exports")
    @RequirePermission(value = "export:execute", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<ExportRequestResponse> createRequest(
            @Valid @RequestBody CreateExportRequest request, BootstrapIdentity identity) {
        return new DataResponse<>(service.createRequest(request, identity));
    }

    @PostMapping("/exports/{exportRequestId}/approve")
    @RequirePermission(value = "export:approve", roles = {UserRole.ADMIN})
    public DataResponse<ExportRequestResponse> approve(
            @PathVariable long exportRequestId,
            @Valid @RequestBody(required = false) ApproveExportRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.approve(exportRequestId, request, true, identity));
    }

    @PostMapping("/exports/{exportRequestId}/reject")
    @RequirePermission(value = "export:approve", roles = {UserRole.ADMIN})
    public DataResponse<ExportRequestResponse> reject(
            @PathVariable long exportRequestId,
            @Valid @RequestBody(required = false) ApproveExportRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.approve(exportRequestId, request, false, identity));
    }

    /** 实际取数下载。每调用一次写一条 {@code export_audit}。 */
    @PostMapping("/exports/{exportRequestId}/download")
    @RequirePermission(value = "export:execute", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public ResponseEntity<byte[]> download(
            @PathVariable long exportRequestId, BootstrapIdentity identity) {
        ExportService.DownloadResult result = service.download(exportRequestId, identity);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.filename() + "\"")
                .header("X-Export-Row-Count", String.valueOf(result.rowCount()))
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(result.csv().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/exports/audits")
    @RequirePermission(value = "export:audit:read", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<List<ExportAuditResponse>> listAudits(BootstrapIdentity identity) {
        return new DataResponse<>(service.listAudits(identity));
    }
}
