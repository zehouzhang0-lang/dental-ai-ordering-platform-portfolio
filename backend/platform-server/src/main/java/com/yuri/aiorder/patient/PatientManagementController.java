package com.yuri.aiorder.patient;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.DataResponse;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.RequirePermission;
import com.yuri.aiorder.order.api.OrderProjectionQueryService.OrderListResponse;
import jakarta.validation.Valid;
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
public class PatientManagementController {

    private final PatientManagementService patientManagementService;

    public PatientManagementController(PatientManagementService patientManagementService) {
        this.patientManagementService = patientManagementService;
    }

    @GetMapping("/patients")
    @RequirePermission(value = "patient:manage-doctor", roles = {UserRole.DOCTOR})
    public DataResponse<OrderListResponse<PatientRecordResponse>> listPatients(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            BootstrapIdentity identity) {
        return new DataResponse<>(patientManagementService.listPatients(identity, keyword, page, size));
    }

    @PostMapping("/patients")
    @RequirePermission(value = "patient:manage-doctor", roles = {UserRole.DOCTOR})
    public DataResponse<PatientRecordResponse> createPatient(
            @Valid @RequestBody CreatePatientRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(patientManagementService.createPatient(request, identity));
    }

    @GetMapping("/patients/{patientId}")
    @RequirePermission(value = "patient:manage-doctor", roles = {UserRole.DOCTOR})
    public DataResponse<PatientRecordResponse> getPatient(
            @PathVariable long patientId,
            BootstrapIdentity identity) {
        return new DataResponse<>(patientManagementService.getPatient(patientId, identity));
    }

    @PutMapping("/patients/{patientId}")
    @RequirePermission(value = "patient:manage-doctor", roles = {UserRole.DOCTOR})
    public DataResponse<PatientRecordResponse> updatePatient(
            @PathVariable long patientId,
            @Valid @RequestBody UpdatePatientRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(patientManagementService.updatePatient(patientId, request, identity));
    }

    @GetMapping("/patients/{patientId}/orders")
    @RequirePermission(value = "patient:manage-doctor", roles = {UserRole.DOCTOR})
    public DataResponse<OrderListResponse<PatientOrderResponse>> listPatientOrders(
            @PathVariable long patientId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            BootstrapIdentity identity) {
        return new DataResponse<>(patientManagementService.listPatientOrders(patientId, identity, page, size));
    }
}
