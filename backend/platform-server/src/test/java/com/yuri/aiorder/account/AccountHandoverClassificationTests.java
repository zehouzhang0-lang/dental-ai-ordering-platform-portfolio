package com.yuri.aiorder.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * TASK-034 D 批次的核心守卫：**每一个用户 ID 列都必须被显式判定**为
 * 「当前负责关系」（交接时转移）或「历史事实」（绝不改）。
 *
 * <p>这条测试存在的理由：账号交接最容易出的事故不是转错，而是**漏判**——
 * 有人后来加了一张带 {@code xxx_user_id} 的表，没人想起要判定它属于哪一类，
 * 于是交接时它既没被转移（新责任人看不到）也没被保护（下次有人图省事全库替换就改了它）。
 * 漏判不会报错，只会在某次交接后让绩效或责任归属悄悄错位。
 *
 * <p>因此这里直接扫 {@code information_schema}：库里有什么就必须判什么，
 * 不依赖任何人记得更新清单。
 */
@SpringBootTest
class AccountHandoverClassificationTests {

    /** 与用户身份无关的同名列，判定时排除。 */
    private static final Set<String> NOT_USER_REFERENCES = Set.of(
            "flyway_schema_history.installed_by");

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void everyUserIdColumnInTheSchemaIsExplicitlyClassified() {
        List<String> columns = jdbcClient.sql("""
                        SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME)
                        FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND (COLUMN_NAME LIKE '%user_id%' OR COLUMN_NAME LIKE '%\\_by')
                        ORDER BY TABLE_NAME, COLUMN_NAME
                        """)
                .query(String.class)
                .list();

        Set<String> transferable = AccountHandoverPlan.TRANSFER_RULES.stream()
                .map(rule -> rule.table() + "." + rule.column())
                .collect(Collectors.toSet());

        List<String> unclassified = columns.stream()
                .filter(column -> !NOT_USER_REFERENCES.contains(column))
                .filter(column -> !transferable.contains(column))
                .filter(column -> !AccountHandoverPlan.HISTORICAL_COLUMNS.contains(column))
                .toList();

        assertThat(unclassified)
                .describedAs("""
                        以下用户 ID 列没有被判定属于「当前负责关系」还是「历史事实」。
                        新增带用户 ID 的列时必须在 AccountHandoverPlan 里做出判断：
                        是交接时要跟着走的当前责任，还是必须保留原样的历史记录。
                        漏判不会报错，只会在某次交接后让绩效或责任归属悄悄错位。""")
                .isEmpty();
    }

    @Test
    void classificationSetsDoNotOverlapAndPointAtRealColumns() {
        Set<String> transferable = AccountHandoverPlan.TRANSFER_RULES.stream()
                .map(rule -> rule.table() + "." + rule.column())
                .collect(Collectors.toSet());
        // 同一列既要转又不能改，说明判定本身是矛盾的。
        assertThat(transferable).doesNotContainAnyElementsOf(AccountHandoverPlan.HISTORICAL_COLUMNS);

        // 清单里写了一个库里不存在的列，等于这条判定从未生效过。
        List<String> existing = jdbcClient.sql("""
                        SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME)
                        FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE()
                        """)
                .query(String.class)
                .list();
        assertThat(existing).containsAll(transferable);
        assertThat(existing).containsAll(AccountHandoverPlan.HISTORICAL_COLUMNS);
    }

    /**
     * 绩效与责任追溯的落点单独再钉一遍。这几列一旦被交接改写，
     * 后果是算错工资、追错责任，而且事后无法区分是交接改的还是本来如此。
     */
    @Test
    void performanceAndAccountabilityColumnsAreNeverTransferable() {
        Set<String> transferable = AccountHandoverPlan.TRANSFER_RULES.stream()
                .map(rule -> rule.table() + "." + rule.column())
                .collect(Collectors.toSet());
        for (String critical : List.of(
                "work_log.worker_user_id",
                "check_record.checker_user_id",
                "final_inspection_report.inspector_user_id",
                "rework_record.closed_by_user_id",
                "production_reward_penalty_record.employee_user_id",
                "order_status_history.operator_user_id")) {
            assertThat(transferable).doesNotContain(critical);
            assertThat(AccountHandoverPlan.HISTORICAL_COLUMNS).contains(critical);
        }
    }
}
