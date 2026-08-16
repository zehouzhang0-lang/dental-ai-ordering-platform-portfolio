package com.yuri.aiorder.clinic;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.DataResponse;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.RequirePermission;
import com.yuri.aiorder.order.api.OrderProjectionQueryService.OrderListResponse;
import jakarta.validation.Valid;
import java.util.Map;
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
public class ClinicController {

    private final ClinicService clinicService;

    public ClinicController(ClinicService clinicService) {
        this.clinicService = clinicService;
    }

    @GetMapping("/clinics")
    @RequirePermission(value = "clinic:read-internal", roles = {UserRole.CS, UserRole.ADMIN})
    public DataResponse<OrderListResponse<ClinicResponse>> listClinics(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            BootstrapIdentity identity) {
        return new DataResponse<>(clinicService.listClinics(identity, keyword, page, size));
    }

    @PostMapping("/clinics")
    @RequirePermission(value = "clinic:create", roles = {UserRole.ADMIN})
    public DataResponse<ClinicResponse> createClinic(
            @Valid @RequestBody CreateClinicRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(clinicService.createClinic(request, identity));
    }

    @GetMapping("/clinics/{clinicId}")
    @RequirePermission(value = {"clinic:read-internal", "clinic:read-self"}, roles = {UserRole.CS, UserRole.ADMIN, UserRole.DOCTOR})
    public DataResponse<ClinicResponse> getClinic(@PathVariable long clinicId, BootstrapIdentity identity) {
        return new DataResponse<>(clinicService.getClinic(clinicId, identity));
    }

    @GetMapping("/clinics/{clinicId}/preference")
    @RequirePermission(value = {"clinic:read-internal", "clinic:read-self"}, roles = {UserRole.CS, UserRole.ADMIN, UserRole.DOCTOR})
    public DataResponse<ClinicPreferenceResponse> getPreference(
            @PathVariable long clinicId,
            BootstrapIdentity identity) {
        return new DataResponse<>(clinicService.getPreference(clinicId, identity));
    }

    @PutMapping("/clinics/{clinicId}/preference")
    @RequirePermission(value = "clinic:preference:write", roles = {UserRole.CS, UserRole.ADMIN})
    public DataResponse<ClinicPreferenceResponse> updatePreference(
            @PathVariable long clinicId,
            @RequestBody Map<String, Object> preferences,
            BootstrapIdentity identity) {
        return new DataResponse<>(clinicService.updatePreference(clinicId, preferences, identity));
    }
}
