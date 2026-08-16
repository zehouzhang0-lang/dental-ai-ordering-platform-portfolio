package com.yuri.aiorder.common.auth;

import com.yuri.aiorder.common.BootstrapIdentity;

public final class IdentityContext {

    private static final ThreadLocal<BootstrapIdentity> CURRENT = new ThreadLocal<>();

    private IdentityContext() {
    }

    public static BootstrapIdentity current() {
        return CURRENT.get();
    }

    public static void set(BootstrapIdentity identity) {
        CURRENT.set(identity);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
