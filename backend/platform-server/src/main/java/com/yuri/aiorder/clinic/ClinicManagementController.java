package com.yuri.aiorder.clinic;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.DataResponse;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.RequirePermission;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "${app.cors.allowed-origin:http://localhost:5173}")
public class ClinicManagementController {

    private final ClinicManagementService service;

    public ClinicManagementController(ClinicManagementService service) {
        this.service = service;
    }

    @GetMapping("/clinics/{clinicId}/management")
    @RequirePermission(value = "clinic:manage", roles = {UserRole.CS, UserRole.ADMIN})
    public DataResponse<ClinicManagementResponse> getManagement(
            @PathVariable long clinicId, BootstrapIdentity identity) {
        return new DataResponse<>(service.getManagement(clinicId, identity));
    }

    @PutMapping("/clinics/{clinicId}/management")
    @RequirePermission(value = "clinic:manage", roles = {UserRole.CS, UserRole.ADMIN})
    public DataResponse<ClinicManagementResponse> updateManagement(
            @PathVariable long clinicId,
            @RequestBody UpdateClinicManagementRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.updateManagement(clinicId, request, identity));
    }

    @PostMapping("/clinics/{clinicId}/blacklist")
    @RequirePermission(value = "clinic:blacklist:manage", roles = {UserRole.CS, UserRole.ADMIN})
    public DataResponse<ClinicManagementResponse> blacklist(
            @PathVariable long clinicId,
            @Valid @RequestBody BlacklistClinicRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.blacklist(clinicId, request, identity));
    }

    @PostMapping("/clinics/{clinicId}/blacklist/release")
    @RequirePermission(value = "clinic:blacklist:manage", roles = {UserRole.CS, UserRole.ADMIN})
    public DataResponse<ClinicManagementResponse> releaseBlacklist(
            @PathVariable long clinicId,
            @Valid @RequestBody ReleaseClinicBlacklistRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.releaseBlacklist(clinicId, request, identity));
    }
}
