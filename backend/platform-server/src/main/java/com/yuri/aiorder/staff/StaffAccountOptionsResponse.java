package com.yuri.aiorder.staff;

import java.util.List;

public record StaffAccountOptionsResponse(
        List<Option> departments,
        List<Option> posts,
        List<PermissionOption> permissions) {

    public record Option(long id, String name) {
    }

    public record PermissionOption(String code, String name) {
    }
}
