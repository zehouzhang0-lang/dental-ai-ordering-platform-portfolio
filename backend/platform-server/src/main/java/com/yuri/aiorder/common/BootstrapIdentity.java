package com.yuri.aiorder.common;

import com.yuri.aiorder.common.auth.IdentityContext;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record BootstrapIdentity(
        UserRole role,
        Long userId,
        Long clinicId,
        String username,
        Set<String> permissions,
        String dataScope) {

    private static volatile boolean bootstrapHeadersAllowed = true;

    public BootstrapIdentity {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public BootstrapIdentity(UserRole role, Long userId, Long clinicId) {
        this(role, userId, clinicId, null, Set.of(), null);
    }

    public static BootstrapIdentity fromHeaders(String roleHeader, Long userId, Long clinicId) {
        BootstrapIdentity authenticated = IdentityContext.current();
        if (authenticated != null) {
            return authenticated;
        }
        if (!bootstrapHeadersAllowed) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing bearer token");
        }
        UserRole role = roleHeader == null || roleHeader.isBlank() ? UserRole.ADMIN : UserRole.valueOf(roleHeader);
        return new BootstrapIdentity(role, userId, clinicId);
    }

    public static void setBootstrapHeadersAllowed(boolean allowed) {
        bootstrapHeadersAllowed = allowed;
    }

    public boolean isDoctor() {
        return role == UserRole.DOCTOR;
    }

    public boolean hasPermission(String permissionCode) {
        return permissions.contains(permissionCode);
    }

    public boolean canAccessDoctorOrder(long doctorUserId, long orderClinicId) {
        return Objects.equals(userId, doctorUserId) || Objects.equals(clinicId, orderClinicId);
    }

    public void requireDoctorScope(long doctorUserId, long orderClinicId) {
        if (!isDoctor()) {
            return;
        }
        if (!canAccessDoctorOrder(doctorUserId, orderClinicId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "doctor cannot access this order");
        }
    }
}
