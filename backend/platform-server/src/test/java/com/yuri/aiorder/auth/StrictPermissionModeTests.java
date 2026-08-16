package com.yuri.aiorder.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.BearerTokenService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.auth.allow-role-fallback=false")
@AutoConfigureMockMvc
class StrictPermissionModeTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BearerTokenService tokenService;

    @Test
    void strictPermissionModeRejectsRoleOnlyTokenWhenPermissionCodeIsRequired() throws Exception {
        String token = tokenService.issue(new BootstrapIdentity(
                UserRole.ADMIN,
                8001L,
                null,
                null,
                Set.of(),
                "ALL"));

        mockMvc.perform(get("/ai/governance/summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void strictPermissionModeAllowsTokenWithRequiredPermissionCode() throws Exception {
        String token = tokenService.issue(new BootstrapIdentity(
                UserRole.ADMIN,
                8001L,
                null,
                null,
                // AI 治理接口的权限码在 TASK-034 A 批次中从 ai:cs 细化为 ai:governance:read。
                Set.of("ai:governance:read"),
                "ALL"));

        mockMvc.perform(get("/ai/governance/summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void strictPermissionModeRejectsDoctorAccountRoleOnlyTokenWhenPermissionCodeIsRequired() throws Exception {
        String token = tokenService.issue(new BootstrapIdentity(
                UserRole.DOCTOR,
                9701L,
                1001L,
                null,
                Set.of(),
                "CLINIC"));

        mockMvc.perform(get("/doctor/account/settings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void strictPermissionModeAllowsDoctorAccountTokenWithRequiredPermissionCode() throws Exception {
        String token = tokenService.issue(new BootstrapIdentity(
                UserRole.DOCTOR,
                9701L,
                1001L,
                null,
                Set.of("account:doctor"),
                "CLINIC"));

        mockMvc.perform(get("/doctor/account/settings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
