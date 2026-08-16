package com.yuri.aiorder.quality;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.DataResponse;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.RequirePermission;
import com.yuri.aiorder.order.api.OrderProjectionQueryService.OrderListResponse;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "${app.cors.allowed-origin:http://localhost:5173}")
public class QualityRecordController {

    private final QualityRecordService qualityRecordService;

    public QualityRecordController(QualityRecordService qualityRecordService) {
        this.qualityRecordService = qualityRecordService;
    }

    @GetMapping("/quality-records")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<OrderListResponse<QualityRecordResponse>> listQualityRecords(
            BootstrapIdentity identity,
            @RequestParam(name = "record_type", required = false) String recordType,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "responsibility_type", required = false) String responsibilityType,
            @RequestParam(name = "order_id", required = false) Long orderId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return new DataResponse<>(
                qualityRecordService.listQualityRecords(identity, recordType, status, responsibilityType, orderId, page, size));
    }

    @PostMapping("/quality-records/external-returns")
    @RequirePermission(value = {"check:write", "message:manage"}, roles = {UserRole.ADMIN, UserRole.CS})
    public DataResponse<QualityRecordResponse> createExternalReturn(
            BootstrapIdentity identity,
            @RequestBody ExternalReturnQualityRecordRequest request) {
        return new DataResponse<>(qualityRecordService.createExternalReturn(identity, request));
    }

    @PutMapping("/quality-records/{qualityRecordId}/status")
    @RequirePermission(value = {"check:write", "message:manage"}, roles = {UserRole.ADMIN, UserRole.CS})
    public DataResponse<QualityRecordResponse> updateQualityRecordStatus(
            BootstrapIdentity identity,
            @PathVariable long qualityRecordId,
            @RequestBody QualityRecordStatusUpdateRequest request) {
        return new DataResponse<>(qualityRecordService.updateStatus(identity, qualityRecordId, request));
    }
}
