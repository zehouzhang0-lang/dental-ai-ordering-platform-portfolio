package com.yuri.aiorder.workflow.execution;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.DataResponse;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.RequirePermission;
import java.time.LocalDate;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
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
public class WorkflowExecutionController {

    private final WorkflowExecutionService workflowExecutionService;

    public WorkflowExecutionController(WorkflowExecutionService workflowExecutionService) {
        this.workflowExecutionService = workflowExecutionService;
    }

    @PostMapping("/check-records")
    @RequirePermission(value = "check:write", roles = {UserRole.ADMIN, UserRole.WORKER})
    public DataResponse<CheckRecordResponse> submitCheck(
            @RequestBody CheckRecordRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(workflowExecutionService.submitCheck(request, identity));
    }

    @GetMapping("/check-records/{nodeInstanceId}")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<List<CheckRecordResponse>> getChecks(
            @PathVariable long nodeInstanceId,
            BootstrapIdentity identity) {
        return new DataResponse<>(workflowExecutionService.getChecks(nodeInstanceId, identity));
    }

    @GetMapping("/reworks")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<List<ReworkRecordResponse>> getReworks(
            BootstrapIdentity identity,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "order_id", required = false) Long orderId,
            @RequestParam(name = "has_impacted_nodes", required = false) Boolean hasImpactedNodes) {
        return new DataResponse<>(workflowExecutionService.getReworks(status, orderId, hasImpactedNodes, identity));
    }

    @GetMapping("/reworks/dictionaries")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<ReworkDictionariesResponse> getReworkDictionaries(BootstrapIdentity identity) {
        return new DataResponse<>(workflowExecutionService.getReworkDictionaries(identity));
    }

    @GetMapping("/reworks/dictionaries/items")
    @RequirePermission(value = "rework:dictionary:manage", roles = UserRole.ADMIN)
    public DataResponse<List<ReworkDictionaryItemResponse>> listReworkDictionaryItems(
            @RequestParam(name = "dictionary_type", required = false) String dictionaryType) {
        return new DataResponse<>(workflowExecutionService.listReworkDictionaryItems(dictionaryType));
    }

    @PostMapping("/reworks/dictionaries/items")
    @RequirePermission(value = "rework:dictionary:manage", roles = UserRole.ADMIN)
    public DataResponse<ReworkDictionaryItemResponse> createReworkDictionaryItem(
            @RequestBody CreateReworkDictionaryItemRequest request) {
        return new DataResponse<>(workflowExecutionService.createReworkDictionaryItem(request));
    }

    @PutMapping("/reworks/dictionaries/items/{itemId}")
    @RequirePermission(value = "rework:dictionary:manage", roles = UserRole.ADMIN)
    public DataResponse<ReworkDictionaryItemResponse> updateReworkDictionaryItem(
            @PathVariable long itemId,
            @RequestBody UpdateReworkDictionaryItemRequest request) {
        return new DataResponse<>(workflowExecutionService.updateReworkDictionaryItem(itemId, request));
    }

    @GetMapping("/production/quality/summary")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<ProductionQualitySummaryResponse> getProductionQualitySummary(
            BootstrapIdentity identity,
            @RequestParam(name = "product_type", required = false) String productType,
            @RequestParam(name = "start_date", required = false) LocalDate startDate,
            @RequestParam(name = "end_date", required = false) LocalDate endDate) {
        return new DataResponse<>(workflowExecutionService.getProductionQualitySummary(
                productType, startDate, endDate, identity));
    }

    @GetMapping("/production/workbench/department-summary")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<ProductionWorkbenchDepartmentSummaryResponse> getProductionWorkbenchDepartmentSummary(
            BootstrapIdentity identity,
            @RequestParam(name = "order_no_prefix", required = false) String orderNoPrefix) {
        return new DataResponse<>(
                workflowExecutionService.getProductionWorkbenchDepartmentSummary(orderNoPrefix, identity));
    }

    @GetMapping("/production/equipment/summary")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<ProductionEquipmentSummaryResponse> getProductionEquipmentSummary(
            BootstrapIdentity identity,
            @RequestParam(name = "equipment_code_prefix", required = false) String equipmentCodePrefix) {
        return new DataResponse<>(workflowExecutionService.getProductionEquipmentSummary(equipmentCodePrefix, identity));
    }

    @GetMapping("/production/equipment")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<List<ProductionEquipmentResponse>> listProductionEquipment(
            BootstrapIdentity identity,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) String status) {
        return new DataResponse<>(workflowExecutionService.listProductionEquipment(keyword, status, identity));
    }

    @GetMapping("/production/equipment/approvals")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<List<ProductionEquipmentEventResponse>> listProductionEquipmentApprovals(
            BootstrapIdentity identity,
            @RequestParam(name = "status", required = false) String status) {
        return new DataResponse<>(workflowExecutionService.listProductionEquipmentApprovals(status, identity));
    }

    @PutMapping("/production/equipment/approvals/{eventId}")
    @RequirePermission(value = "check:write", roles = UserRole.ADMIN)
    public DataResponse<ProductionEquipmentEventResponse> decideProductionEquipmentApproval(
            BootstrapIdentity identity,
            @PathVariable long eventId,
            @Valid @RequestBody ProductionEquipmentApprovalRequest request) {
        return new DataResponse<>(workflowExecutionService.decideProductionEquipmentApproval(eventId, request, identity));
    }

    @GetMapping("/production/equipment/{equipmentCode}")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<ProductionEquipmentDetailResponse> getProductionEquipment(
            BootstrapIdentity identity,
            @PathVariable String equipmentCode) {
        return new DataResponse<>(workflowExecutionService.getProductionEquipment(equipmentCode, identity));
    }

    @PostMapping("/production/equipment")
    @RequirePermission(value = "check:write", roles = {UserRole.ADMIN, UserRole.WORKER})
    public DataResponse<ProductionEquipmentResponse> createProductionEquipment(
            BootstrapIdentity identity,
            @Valid @RequestBody ProductionEquipmentRequest request) {
        return new DataResponse<>(workflowExecutionService.createProductionEquipment(request, identity));
    }

    @PostMapping("/production/equipment/{equipmentCode}/events")
    @RequirePermission(value = "check:write", roles = {UserRole.ADMIN, UserRole.WORKER})
    public DataResponse<ProductionEquipmentEventResponse> createProductionEquipmentEvent(
            BootstrapIdentity identity,
            @PathVariable String equipmentCode,
            @Valid @RequestBody ProductionEquipmentEventRequest request) {
        return new DataResponse<>(
                workflowExecutionService.createProductionEquipmentEvent(equipmentCode, request, identity));
    }

    @GetMapping("/production/material-exceptions/summary")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<ProductionMaterialExceptionSummaryResponse> getProductionMaterialExceptionSummary(
            BootstrapIdentity identity,
            @RequestParam(name = "exception_no_prefix", required = false) String exceptionNoPrefix) {
        return new DataResponse<>(
                workflowExecutionService.getProductionMaterialExceptionSummary(exceptionNoPrefix, identity));
    }

    @GetMapping("/production/material-exceptions")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<List<ProductionMaterialExceptionResponse>> listProductionMaterialExceptions(
            BootstrapIdentity identity,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) String status) {
        return new DataResponse<>(workflowExecutionService.listProductionMaterialExceptions(keyword, status, identity));
    }

    @GetMapping("/production/material-exceptions/{exceptionNo}")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<ProductionMaterialExceptionResponse> getProductionMaterialException(
            BootstrapIdentity identity,
            @PathVariable String exceptionNo) {
        return new DataResponse<>(workflowExecutionService.getProductionMaterialException(exceptionNo, identity));
    }

    @PostMapping("/production/material-exceptions")
    @RequirePermission(value = "check:write", roles = {UserRole.ADMIN, UserRole.WORKER})
    public DataResponse<ProductionMaterialExceptionResponse> createProductionMaterialException(
            BootstrapIdentity identity,
            @Valid @RequestBody ProductionMaterialExceptionRequest request) {
        return new DataResponse<>(workflowExecutionService.createProductionMaterialException(request, identity));
    }

    @PutMapping("/production/material-exceptions/{exceptionNo}/status")
    @RequirePermission(value = "check:write", roles = {UserRole.ADMIN, UserRole.WORKER})
    public DataResponse<ProductionMaterialExceptionResponse> updateProductionMaterialExceptionStatus(
            BootstrapIdentity identity,
            @PathVariable String exceptionNo,
            @Valid @RequestBody ProductionMaterialExceptionStatusRequest request) {
        return new DataResponse<>(
                workflowExecutionService.updateProductionMaterialExceptionStatus(exceptionNo, request, identity));
    }

    @GetMapping("/production/safety-environment/summary")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<ProductionSafetyEnvironmentSummaryResponse> getProductionSafetyEnvironmentSummary(
            BootstrapIdentity identity,
            @RequestParam(name = "event_no_prefix", required = false) String eventNoPrefix) {
        return new DataResponse<>(
                workflowExecutionService.getProductionSafetyEnvironmentSummary(eventNoPrefix, identity));
    }

    @GetMapping("/production/safety-environment/events")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<List<ProductionSafetyEnvironmentEventResponse>> listProductionSafetyEnvironmentEvents(
            BootstrapIdentity identity,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) String status) {
        return new DataResponse<>(workflowExecutionService.listProductionSafetyEnvironmentEvents(keyword, status, identity));
    }

    @GetMapping("/production/safety-environment/events/{eventNo}")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<ProductionSafetyEnvironmentEventResponse> getProductionSafetyEnvironmentEvent(
            BootstrapIdentity identity,
            @PathVariable String eventNo) {
        return new DataResponse<>(workflowExecutionService.getProductionSafetyEnvironmentEvent(eventNo, identity));
    }

    @GetMapping("/production/safety-environment/rules")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<List<ProductionSafetyRuleResponse>> listProductionSafetyRules(BootstrapIdentity identity) {
        return new DataResponse<>(workflowExecutionService.listProductionSafetyRules(identity));
    }

    @PostMapping("/production/safety-environment/events")
    @RequirePermission(value = "check:write", roles = {UserRole.ADMIN, UserRole.WORKER})
    public DataResponse<ProductionSafetyEnvironmentEventResponse> createProductionSafetyEnvironmentEvent(
            BootstrapIdentity identity,
            @Valid @RequestBody ProductionSafetyEnvironmentEventRequest request) {
        return new DataResponse<>(
                workflowExecutionService.createProductionSafetyEnvironmentEvent(request, identity));
    }

    @PutMapping("/production/safety-environment/events/{eventNo}/status")
    @RequirePermission(value = "check:write", roles = {UserRole.ADMIN, UserRole.WORKER})
    public DataResponse<ProductionSafetyEnvironmentEventResponse> updateProductionSafetyEnvironmentEventStatus(
            BootstrapIdentity identity,
            @PathVariable String eventNo,
            @Valid @RequestBody ProductionSafetyEnvironmentEventStatusRequest request) {
        return new DataResponse<>(
                workflowExecutionService.updateProductionSafetyEnvironmentEventStatus(eventNo, request, identity));
    }

    @GetMapping("/production/cost-management/summary")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<ProductionCostSummaryResponse> getProductionCostSummary(
            BootstrapIdentity identity,
            @RequestParam(name = "cost_no_prefix", required = false) String costNoPrefix) {
        return new DataResponse<>(workflowExecutionService.getProductionCostSummary(costNoPrefix, identity));
    }

    @GetMapping("/production/cost-management/records")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<List<ProductionCostRecordResponse>> listProductionCostRecords(
            BootstrapIdentity identity,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) String status) {
        return new DataResponse<>(workflowExecutionService.listProductionCostRecords(keyword, status, identity));
    }

    @GetMapping("/production/cost-management/records/{costNo}")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<ProductionCostRecordResponse> getProductionCostRecord(
            BootstrapIdentity identity,
            @PathVariable String costNo) {
        return new DataResponse<>(workflowExecutionService.getProductionCostRecord(costNo, identity));
    }

    @PutMapping("/production/cost-management/records/{costNo}/status")
    @RequirePermission(value = "check:write", roles = UserRole.ADMIN)
    public DataResponse<ProductionCostRecordResponse> updateProductionCostRecordStatus(
            BootstrapIdentity identity,
            @PathVariable String costNo,
            @Valid @RequestBody ProductionCostStatusRequest request) {
        return new DataResponse<>(workflowExecutionService.updateProductionCostRecordStatus(costNo, request, identity));
    }

    @GetMapping("/production/outsourcing")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<List<ProductionOutsourcingBatchResponse>> listProductionOutsourcing(
            BootstrapIdentity identity,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) String status) {
        return new DataResponse<>(workflowExecutionService.listProductionOutsourcing(keyword, status, identity));
    }

    @GetMapping("/production/outsourcing/{batchNo}")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<ProductionOutsourcingBatchResponse> getProductionOutsourcing(
            BootstrapIdentity identity,
            @PathVariable String batchNo) {
        return new DataResponse<>(workflowExecutionService.getProductionOutsourcing(batchNo, identity));
    }

    @PostMapping("/production/cost-management/records")
    @RequirePermission(value = "check:write", roles = {UserRole.ADMIN, UserRole.WORKER})
    public DataResponse<ProductionCostRecordResponse> createProductionCostRecord(
            BootstrapIdentity identity,
            @Valid @RequestBody ProductionCostRecordRequest request) {
        return new DataResponse<>(workflowExecutionService.createProductionCostRecord(request, identity));
    }

    @GetMapping("/production/reward-penalty/summary")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<ProductionRewardPenaltySummaryResponse> getProductionRewardPenaltySummary(
            BootstrapIdentity identity,
            @RequestParam(name = "record_no_prefix", required = false) String recordNoPrefix) {
        return new DataResponse<>(
                workflowExecutionService.getProductionRewardPenaltySummary(recordNoPrefix, identity));
    }

    @PostMapping("/production/reward-penalty/records")
    @RequirePermission(value = "check:write", roles = {UserRole.ADMIN, UserRole.WORKER})
    public DataResponse<ProductionRewardPenaltyRecordResponse> createProductionRewardPenaltyRecord(
            BootstrapIdentity identity,
            @Valid @RequestBody ProductionRewardPenaltyRecordRequest request) {
        return new DataResponse<>(
                workflowExecutionService.createProductionRewardPenaltyRecord(request, identity));
    }

    @PutMapping("/production/reward-penalty/records/{recordNo}/status")
    @RequirePermission(value = "check:write", roles = {UserRole.ADMIN, UserRole.WORKER})
    public DataResponse<ProductionRewardPenaltyRecordResponse> updateProductionRewardPenaltyRecordStatus(
            BootstrapIdentity identity,
            @PathVariable String recordNo,
            @Valid @RequestBody ProductionRewardPenaltyStatusRequest request) {
        return new DataResponse<>(
                workflowExecutionService.updateProductionRewardPenaltyRecordStatus(recordNo, request, identity));
    }

    @PostMapping("/reworks/{reworkId}/close")
    @RequirePermission(value = "check:write", roles = {UserRole.ADMIN, UserRole.WORKER})
    public DataResponse<ReworkRecordResponse> closeRework(
            @PathVariable long reworkId,
            @RequestBody ReworkCloseRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(workflowExecutionService.closeRework(reworkId, request, identity));
    }

    @PostMapping("/final-inspection-reports")
    @RequirePermission("final-inspection:manage")
    public DataResponse<FinalInspectionReportResponse> createFinalInspectionReport(
            @RequestBody FinalInspectionReportRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(workflowExecutionService.createFinalInspectionReport(request, identity));
    }

    @GetMapping("/final-inspection-reports/{orderId}")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<FinalInspectionReportResponse> getFinalInspectionReport(
            @PathVariable long orderId,
            @RequestParam(name = "allow_absent", defaultValue = "false") boolean allowAbsent,
            BootstrapIdentity identity) {
        return new DataResponse<>(workflowExecutionService.getFinalInspectionReport(orderId, identity, allowAbsent));
    }

    @PostMapping("/work-logs/start")
    @RequirePermission(value = "worklog:write-self", roles = {UserRole.ADMIN, UserRole.WORKER})
    public DataResponse<WorkLogResponse> startWorkLog(
            @RequestBody WorkLogStartRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(workflowExecutionService.startWorkLog(request, identity));
    }

    @PostMapping("/work-logs/{workLogId}/pause")
    @RequirePermission(value = "worklog:write-self", roles = {UserRole.ADMIN, UserRole.WORKER})
    public DataResponse<WorkLogResponse> pauseWorkLog(
            @PathVariable long workLogId,
            BootstrapIdentity identity) {
        return new DataResponse<>(workflowExecutionService.pauseWorkLog(workLogId, identity));
    }

    @PostMapping("/work-logs/{workLogId}/resume")
    @RequirePermission(value = "worklog:write-self", roles = {UserRole.ADMIN, UserRole.WORKER})
    public DataResponse<WorkLogResponse> resumeWorkLog(
            @PathVariable long workLogId,
            BootstrapIdentity identity) {
        return new DataResponse<>(workflowExecutionService.resumeWorkLog(workLogId, identity));
    }

    @PostMapping("/work-logs/{workLogId}/finish")
    @RequirePermission(value = "worklog:write-self", roles = {UserRole.ADMIN, UserRole.WORKER})
    public DataResponse<WorkLogResponse> finishWorkLog(
            @PathVariable long workLogId,
            BootstrapIdentity identity) {
        return new DataResponse<>(workflowExecutionService.finishWorkLog(workLogId, identity));
    }

    @GetMapping("/performance")
    @RequirePermission(value = {"performance:read-all", "performance:read-self"}, roles = {
            UserRole.ADMIN, UserRole.WORKER})
    public DataResponse<PerformanceStatsResponse> getPerformance(
            BootstrapIdentity identity,
            @RequestParam(name = "user_id", required = false) Long requestedUserId,
            @RequestParam(name = "start_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return new DataResponse<>(workflowExecutionService.getPerformance(requestedUserId, startDate, endDate, identity));
    }

    @GetMapping("/performance/details")
    @RequirePermission(value = {"performance:read-all", "performance:read-self"}, roles = {
            UserRole.ADMIN, UserRole.WORKER})
    public DataResponse<List<PerformanceDetailResponse>> getPerformanceDetails(
            BootstrapIdentity identity,
            @RequestParam(name = "user_id", required = false) Long requestedUserId,
            @RequestParam(name = "start_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return new DataResponse<>(workflowExecutionService.getPerformanceDetails(requestedUserId, startDate, endDate, identity));
    }
}
