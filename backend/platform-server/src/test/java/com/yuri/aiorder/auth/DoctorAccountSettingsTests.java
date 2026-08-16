package com.yuri.aiorder.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.BearerTokenService;
import com.yuri.aiorder.common.auth.PasswordHashService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DoctorAccountSettingsTests {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BearerTokenService tokenService;

    @Autowired
    private PasswordHashService passwordHashService;

    private long clinicId;
    private long doctorUserId;
    private String username;
    private String accessToken;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        username = "doctor-account-" + suffix.substring(0, 10);
        jdbcClient.sql("INSERT INTO clinic (clinic_name) VALUES (:clinicName)")
                .param("clinicName", "医生账号设置诊所-" + suffix)
                .update();
        clinicId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        doctorUserId = 990000L + Math.abs(suffix.hashCode());
        jdbcClient.sql("""
                        INSERT INTO system_user
                            (user_id, username, password_hash, display_name, clinic_id, user_type, status)
                        VALUES
                            (:userId, :username, :passwordHash, '账号设置医生', :clinicId, 'DOCTOR', 'ACTIVE')
                        """)
                .param("userId", doctorUserId)
                .param("username", username)
                .param("passwordHash", passwordHashService.hash("old-password"))
                .param("clinicId", clinicId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO system_user_role (user_id, role_id)
                        SELECT :userId, role_id FROM system_role WHERE role_code = 'DOCTOR'
                        """)
                .param("userId", doctorUserId)
                .update();
        accessToken = tokenService.issue(new BootstrapIdentity(
                UserRole.DOCTOR,
                doctorUserId,
                clinicId,
                username,
                Set.of("order:read-doctor", "ai:doctor"),
                "CLINIC"));
    }

    @Test
    void doctorCanReadAndUpdateOwnAccountSettings() throws Exception {
        mockMvc.perform(get("/doctor/account/settings")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user_id").value(doctorUserId))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.notification_push_enabled").value(true))
                .andExpect(content().string(not(containsString("password_hash"))));

        mockMvc.perform(put("/doctor/account/settings")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "display_name": "林医生",
                                  "contact_email": "doctor@example.com",
                                  "contact_phone": "00000000001",
                                  "shipping_address": "上海市徐汇区一期验收地址",
                                  "notification_push_enabled": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.display_name").value("林医生"))
                .andExpect(jsonPath("$.data.contact_email").value("doctor@example.com"))
                .andExpect(jsonPath("$.data.contact_phone").value("00000000001"))
                .andExpect(jsonPath("$.data.shipping_address").value("上海市徐汇区一期验收地址"))
                .andExpect(jsonPath("$.data.notification_push_enabled").value(false));
    }

    @Test
    void doctorCanChangePasswordAndOldPasswordStopsWorking() throws Exception {
        mockMvc.perform(post("/doctor/account/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\":\"wrong\",\"new_password\":\"new-password-9d89\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/doctor/account/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\":\"old-password\",\"new_password\":\"new-password-9d89\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"old-password\",\"portal\":\"DOCTOR\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"new-password-9d89\",\"portal\":\"DOCTOR\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void csCannotAccessDoctorSelfServiceSettings() throws Exception {
        String csToken = tokenService.issue(new BootstrapIdentity(
                UserRole.CS,
                8002L,
                null,
                "cs",
                Set.of("message:manage"),
                "ALL"));
        mockMvc.perform(get("/doctor/account/settings")
                        .header("Authorization", "Bearer " + csToken))
                .andExpect(status().isForbidden());
    }
}
