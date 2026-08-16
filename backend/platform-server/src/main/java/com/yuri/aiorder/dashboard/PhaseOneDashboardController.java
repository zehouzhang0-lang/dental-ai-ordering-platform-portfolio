package com.yuri.aiorder.dashboard;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.DataResponse;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.RequirePermission;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "${app.cors.allowed-origin:http://localhost:5173}")
public class PhaseOneDashboardController {

    private final PhaseOneDashboardService phaseOneDashboardService;

    public PhaseOneDashboardController(PhaseOneDashboardService phaseOneDashboardService) {
        this.phaseOneDashboardService = phaseOneDashboardService;
    }

    @GetMapping("/dashboards/phase-one-ab")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<PhaseOneDashboardResponse> getPhaseOneAbDashboard(BootstrapIdentity identity) {
        return new DataResponse<>(phaseOneDashboardService.getPhaseOneAbDashboard(identity));
    }
}
