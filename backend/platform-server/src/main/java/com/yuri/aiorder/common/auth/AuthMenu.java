package com.yuri.aiorder.common.auth;

public record AuthMenu(
        String menuCode,
        String menuName,
        String menuType,
        String routePath,
        String componentPath,
        String permissionCode,
        String icon,
        Integer sortOrder) {
}
