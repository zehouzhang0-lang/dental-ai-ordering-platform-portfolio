package com.yuri.aiorder.account;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.DataResponse;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequirePermission(value = "account:doctor", roles = UserRole.DOCTOR)
public class DoctorAccountController {

    private final DoctorAccountService accountService;

    public DoctorAccountController(DoctorAccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/doctor/account/settings")
    public DataResponse<DoctorAccountSettingsResponse> getSettings(BootstrapIdentity identity) {
        return new DataResponse<>(accountService.getSettings(identity));
    }

    @PutMapping("/doctor/account/settings")
    public DataResponse<DoctorAccountSettingsResponse> updateSettings(
            BootstrapIdentity identity,
            @RequestBody DoctorAccountSettingsRequest request) {
        return new DataResponse<>(accountService.updateSettings(identity, request));
    }

    @PostMapping("/doctor/account/password")
    public DataResponse<DoctorAccountSettingsResponse> updatePassword(
            BootstrapIdentity identity,
            @RequestBody DoctorPasswordUpdateRequest request) {
        return new DataResponse<>(accountService.updatePassword(identity, request));
    }
}
