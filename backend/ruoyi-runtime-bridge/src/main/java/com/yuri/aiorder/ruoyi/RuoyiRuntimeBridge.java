package com.yuri.aiorder.ruoyi;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;

/**
 * Stable boundary between the existing application runtime and the pinned RuoYi-Vue-Pro source.
 */
public final class RuoyiRuntimeBridge {

    public static final String MODE = "incremental";
    public static final String SOURCE_REVISION = "2026.06-SNAPSHOT";
    public static final String SOURCE_COMMIT = "ec3f7cbf73e88514a70a6b59d365092ee470603d";
    public static final int BEARER_FILTER_ORDER = WebFilterOrderEnum.TENANT_SECURITY_FILTER;

    private RuoyiRuntimeBridge() {
    }
}
