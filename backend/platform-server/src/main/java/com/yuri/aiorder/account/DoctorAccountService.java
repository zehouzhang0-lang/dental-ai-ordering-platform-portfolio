package com.yuri.aiorder.account;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.PasswordHashService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DoctorAccountService {

    private final JdbcClient jdbcClient;
    private final PasswordHashService passwordHashService;

    public DoctorAccountService(JdbcClient jdbcClient, PasswordHashService passwordHashService) {
        this.jdbcClient = jdbcClient;
        this.passwordHashService = passwordHashService;
    }

    public DoctorAccountSettingsResponse getSettings(BootstrapIdentity identity) {
        requireDoctor(identity);
        return loadSettings(requireUserId(identity));
    }

    public DoctorAccountSettingsResponse updateSettings(
            BootstrapIdentity identity,
            DoctorAccountSettingsRequest request) {
        requireDoctor(identity);
        long userId = requireUserId(identity);
        jdbcClient.sql("""
                        UPDATE system_user
                        SET display_name = :displayName,
                            contact_email = :contactEmail,
                            contact_phone = :contactPhone,
                            shipping_address = :shippingAddress,
                            notification_push_enabled = :notificationPushEnabled
                        WHERE user_id = :userId
                          AND user_type = 'DOCTOR'
                          AND status = 'ACTIVE'
                        """)
                .param("displayName", requiredText(request.displayName(), "display_name is required"))
                .param("contactEmail", optionalText(request.contactEmail()))
                .param("contactPhone", optionalText(request.contactPhone()))
                .param("shippingAddress", optionalText(request.shippingAddress()))
                .param("notificationPushEnabled", Boolean.FALSE.equals(request.notificationPushEnabled()) ? 0 : 1)
                .param("userId", userId)
                .update();
        return loadSettings(userId);
    }

    public DoctorAccountSettingsResponse updatePassword(
            BootstrapIdentity identity,
            DoctorPasswordUpdateRequest request) {
        requireDoctor(identity);
        long userId = requireUserId(identity);
        String currentHash = loadPasswordHash(userId);
        if (!passwordHashService.matches(requiredText(request.currentPassword(), "current_password is required"), currentHash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "current password is incorrect");
        }
        String newPassword = requiredText(request.newPassword(), "new_password is required");
        if (newPassword.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "new_password must be at least 8 characters");
        }
        jdbcClient.sql("""
                        UPDATE system_user
                        SET password_hash = :passwordHash
                        WHERE user_id = :userId
                          AND user_type = 'DOCTOR'
                          AND status = 'ACTIVE'
                        """)
                .param("passwordHash", passwordHashService.hash(newPassword))
                .param("userId", userId)
                .update();
        return loadSettings(userId);
    }

    private DoctorAccountSettingsResponse loadSettings(long userId) {
        try {
            return jdbcClient.sql("""
                            SELECT user_id, username, display_name, contact_email, contact_phone,
                                   shipping_address, notification_push_enabled
                            FROM system_user
                            WHERE user_id = :userId
                              AND user_type = 'DOCTOR'
                              AND status = 'ACTIVE'
                            """)
                    .param("userId", userId)
                    .query((rs, rowNum) -> new DoctorAccountSettingsResponse(
                            rs.getLong("user_id"),
                            rs.getString("username"),
                            rs.getString("display_name"),
                            rs.getString("contact_email"),
                            rs.getString("contact_phone"),
                            rs.getString("shipping_address"),
                            rs.getBoolean("notification_push_enabled")))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "doctor account not found", ex);
        }
    }

    private String loadPasswordHash(long userId) {
        try {
            return jdbcClient.sql("""
                            SELECT password_hash
                            FROM system_user
                            WHERE user_id = :userId
                              AND user_type = 'DOCTOR'
                              AND status = 'ACTIVE'
                            """)
                    .param("userId", userId)
                    .query(String.class)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "doctor account not found", ex);
        }
    }

    private void requireDoctor(BootstrapIdentity identity) {
        if (identity.role() != UserRole.DOCTOR) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "doctor account settings are doctor only");
        }
    }

    private long requireUserId(BootstrapIdentity identity) {
        if (identity.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "doctor user id is required");
        }
        return identity.userId();
    }

    private String requiredText(String value, String message) {
        String normalized = optionalText(value);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return normalized;
    }

    private String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
