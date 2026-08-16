package com.yuri.aiorder.rbac;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 密码只能重置不能查看：这里返回的是一次性初始口令，不是原口令，也不落审计。 */
public record RbacPasswordResetResponse(
        @JsonProperty("user_id") long userId,
        String username,
        @JsonProperty("temporary_password") String temporaryPassword) {
}
