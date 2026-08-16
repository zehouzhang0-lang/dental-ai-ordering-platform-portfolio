package com.yuri.aiorder.staff;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.DataResponse;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.RequirePermission;
import jakarta.validation.Valid;
import com.yuri.aiorder.order.api.OrderProjectionQueryService.OrderListResponse;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "${app.cors.allowed-origin:http://localhost:5173}")
public class StaffWorkloadController {

    private final StaffWorkloadService staffWorkloadService;
    private final StaffAccountService staffAccountService;

    public StaffWorkloadController(
            StaffWorkloadService staffWorkloadService,
            StaffAccountService staffAccountService) {
        this.staffWorkloadService = staffWorkloadService;
        this.staffAccountService = staffAccountService;
    }

    @GetMapping("/staff/workload")
    @RequirePermission(value = {"workflow:read-internal", "performance:read-self"}, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.WORKER})
    public DataResponse<OrderListResponse<StaffWorkloadResponse>> listStaffWorkload(
            BootstrapIdentity identity,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return new DataResponse<>(staffWorkloadService.listStaffWorkload(identity, keyword, page, size));
    }

    @GetMapping("/staff/account-options")
    @RequirePermission(value = "staff:manage", roles = UserRole.ADMIN)
    public DataResponse<StaffAccountOptionsResponse> getAccountOptions(BootstrapIdentity identity) {
        return new DataResponse<>(staffAccountService.getAccountOptions(identity));
    }

    @PostMapping("/staff/accounts")
    @RequirePermission(value = "staff:manage", roles = UserRole.ADMIN)
    public DataResponse<StaffAccountResponse> createWorkerAccount(
            @Valid @RequestBody StaffAccountCreateRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(staffAccountService.createWorker(request, identity));
    }

    @PutMapping("/staff/accounts/{userId}")
    @RequirePermission(value = "staff:manage", roles = UserRole.ADMIN)
    public DataResponse<StaffAccountResponse> updateWorkerAccount(
            @PathVariable long userId,
            @RequestBody StaffAccountUpdateRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(staffAccountService.updateWorker(userId, request, identity));
    }
}
