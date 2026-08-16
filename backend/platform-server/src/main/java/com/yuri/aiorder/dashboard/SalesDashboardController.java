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
public class SalesDashboardController {

    private final SalesDashboardService salesDashboardService;

    public SalesDashboardController(SalesDashboardService salesDashboardService) {
        this.salesDashboardService = salesDashboardService;
    }

    @GetMapping("/dashboards/sales")
    @RequirePermission(value = "check:read-internal", roles = {UserRole.ADMIN, UserRole.CS})
    public DataResponse<SalesDashboardResponse> getSalesDashboard(BootstrapIdentity identity) {
        return new DataResponse<>(salesDashboardService.getSalesDashboard(identity));
    }
}
