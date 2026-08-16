package com.yuri.aiorder.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 业务时区。**凡是要判断「今天」「哪一天」的地方都必须走这里**，不要用无参的
 * {@code LocalDate.now()}——那取的是 JVM 默认时区，容器里默认是 UTC。
 *
 * <p>这不是洁癖。工厂在中国，业务上的「今天」是 Asia/Shanghai 的今天；而部署用的
 * {@code eclipse-temurin} 与 {@code mysql} 官方镜像都不带时区设置，默认 UTC。
 * 两者混用的后果已经出现过一次：{@code ProductionWorkbenchDepartmentSummaryTests} 在本地
 * 00:00–08:00 期间必然失败，因为服务按 Asia/Shanghai 算「今天」而 MySQL 的
 * {@code CURRENT_TIMESTAMP} 给的是 UTC，生产环境的「今日」指标同样会错 8 小时。
 *
 * <p>完整的修法有两半，缺一不可：
 * <ol>
 *   <li>数据库侧：MySQL 以 {@code --default-time-zone=+08:00} 启动，让
 *       {@code CURRENT_TIMESTAMP(3)} 写入的就是业务时区的墙上时间；</li>
 *   <li>应用侧：Java 里所有「今天」都用本类，即使 JVM 时区没设对也算得出正确的业务日期。</li>
 * </ol>
 * 两处的一致性由 {@code npm run check:deployment-env} 静态守住。
 */
public final class BusinessTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private BusinessTime() {
    }

    /** 业务时区的今天。 */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** 业务时区的当前时刻。 */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }
}
