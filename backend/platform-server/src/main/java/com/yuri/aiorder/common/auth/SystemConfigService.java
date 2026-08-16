package com.yuri.aiorder.common.auth;

import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 运行时配置开关。
 *
 * <p>客户确认表里有三项自相矛盾或未填写的内容（高级客服保留还是取消、管理端能否代操作生产、
 * 生产资料审核员取消后由谁承接）。这些项**不写死在代码里**：客户回复前先按默认值运行，
 * 回复后改配置即可，不需要改代码重新发版。
 *
 * <p>不做缓存，理由与 {@link RolePermissionCatalog} 相同：改完配置必须立即生效。
 */
@Service
public class SystemConfigService {

    public static final String CS_SENIOR_ENABLED = "role.cs-senior.enabled";
    public static final String ADMIN_CAN_OPERATE_PRODUCTION = "role.admin.can-operate-production";
    public static final String PRODUCTION_DATA_REVIEWER_SUCCESSOR = "role.production-data-reviewer.successor";

    private final JdbcClient jdbcClient;

    public SystemConfigService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public String get(String configKey, String defaultValue) {
        return jdbcClient.sql("SELECT config_value FROM system_config WHERE config_key = :configKey")
                .param("configKey", configKey)
                .query(String.class)
                .optional()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .orElse(defaultValue);
    }

    public boolean isEnabled(String configKey, boolean defaultValue) {
        return Boolean.parseBoolean(get(configKey, String.valueOf(defaultValue)).toLowerCase(Locale.ROOT));
    }

    /** 管理端是否可以代替技工执行开工 / 暂停 / 完工。客户否决了「一律不能」，但未写允许到什么程度，默认关闭。 */
    public boolean adminCanOperateProduction() {
        return isEnabled(ADMIN_CAN_OPERATE_PRODUCTION, false);
    }
}
