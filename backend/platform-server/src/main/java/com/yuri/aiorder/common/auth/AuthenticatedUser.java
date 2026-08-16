package com.yuri.aiorder.common.auth;

import com.yuri.aiorder.common.BootstrapIdentity;
import java.util.List;

public record AuthenticatedUser(
        String username,
        Long userId,
        Long clinicId,
        List<String> roles,
        List<String> permissions,
        List<AuthMenu> menus,
        String dataScope,
        BootstrapIdentity identity) {
}
