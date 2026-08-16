package com.yuri.aiorder.common.auth;

import com.yuri.aiorder.common.UserRole;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    String[] value() default {};

    UserRole[] roles() default {};
}
