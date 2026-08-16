package com.yuri.aiorder.account;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuri.aiorder.account.AccountHandoverModels.HandoverItemResponse;
import com.yuri.aiorder.account.AccountHandoverModels.HandoverPreviewResponse;
import com.yuri.aiorder.account.AccountHandoverModels.HandoverRequest;
import com.yuri.aiorder.account.AccountHandoverModels.HandoverResponse;
import com.yuri.aiorder.account.AccountHandoverPlan.TransferRule;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.BusinessTime;
import com.yuri.aiorder.common.auth.AccessControlService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 账号交接与人员转移。
 *
 * <p>客户原话：「有分配功能，把他账号分配给新同事，并保留之前得服务记录」。
 *
 * <p>转移的是 {@link AccountHandoverPlan#TRANSFER_RULES} 里的 9 条「当前负责关系」；
 * 其余 60 多个用户 ID 列全部是历史事实，一个都不动。分类依据与守卫见
 * {@code AccountHandoverPlan} 的类注释。
 *
 * <p>三条边界，缺一条这个功能就会变成数据事故：
 * <ul>
 *   <li>承接人必须与原责任人是**同一入口角色**。把医生的病例转给客服，
 *       接手的人根本进不了医生端，数据还会落在他看不见的数据范围外。</li>
 *   <li>医生之间的交接必须**同诊所**——跨诊所转病例等于把患者资料给了另一家客户。</li>
 *   <li>操作人需要 {@code account:handover}；跨部门另需 {@code rbac:cross-dept}，
 *       与 C 批次的授权边界保持同一套口径。</li>
 * </ul>
 */
@Service
public class AccountHandoverService {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;

    public AccountHandoverService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            AccessControlService accessControlService) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
    }

    public HandoverPreviewResponse preview(
            long fromUserId, long successorUserId, BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, "account:handover", "account handover requires account:handover");
        UserRow source = loadUser(fromUserId);
        UserRow successor = loadUser(successorUserId);
        validatePair(source, successor, identity);

        List<HandoverItemResponse> items = collect(source);
        int total = items.stream().mapToInt(HandoverItemResponse::affectedCount).sum();
        return new HandoverPreviewResponse(
                source.userId(), source.displayName(),
                successor.userId(), successor.displayName(),
                source.userType(), total, items, historicalRecordsKept());
    }

    @Transactional
    public HandoverResponse handover(
            long fromUserId, HandoverRequest request, BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, "account:handover", "account handover requires account:handover");
        // 交接是不可逆的批量改写，与导出同理：界面上的确认框绕得过去，接口调用绕不过去。
        if (!Boolean.TRUE.equals(request.acknowledged())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "acknowledged must be true; account handover rewrites ownership in bulk");
        }
        boolean disableSource = Boolean.TRUE.equals(request.disableSourceAccount());
        if (disableSource) {
            // 停用账号本身是账号安全操作，权限码与 C 批次保持一致，不因为「顺带」而放宽。
            accessControlService.requirePermission(
                    identity, "account:disable", "disabling an account requires account:disable");
        }

        UserRow source = lockUser(fromUserId);
        UserRow successor = loadUser(request.successorUserId());
        validatePair(source, successor, identity);

        List<HandoverItemResponse> planned = collect(source);
        String handoverNo = nextHandoverNo();
        int total = planned.stream().mapToInt(HandoverItemResponse::affectedCount).sum();
        jdbcClient.sql("""
                        INSERT INTO account_handover
                            (handover_no, from_user_id, to_user_id, operator_user_id,
                             reason, source_disabled, transferred_object_count)
                        VALUES
                            (:handoverNo, :fromUserId, :toUserId, :operatorUserId,
                             :reason, :sourceDisabled, :total)
                        """)
                .param("handoverNo", handoverNo)
                .param("fromUserId", source.userId())
                .param("toUserId", successor.userId())
                .param("operatorUserId", identity.userId())
                .param("reason", blankToNull(request.reason()))
                .param("sourceDisabled", disableSource)
                .param("total", total)
                .update();
        long handoverId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();

        for (HandoverItemResponse item : planned) {
            TransferRule rule = ruleOf(item.objectType());
            if (item.affectedCount() > 0) {
                jdbcClient.sql("""
                                UPDATE %s
                                SET %s = :toUserId
                                WHERE %s = :fromUserId
                                  %s
                                """.formatted(
                                rule.table(), rule.column(), rule.column(), extraCondition(rule)))
                        .param("toUserId", successor.userId())
                        .param("fromUserId", source.userId())
                        .update();
            }
            // 数量为 0 的条目也落一条：事后追溯时「这一类当时就没有对象」
            // 和「这一类根本没被考虑过」是两回事。
            jdbcClient.sql("""
                            INSERT INTO account_handover_item
                                (handover_id, object_type, object_label, target_table,
                                 target_column, affected_count, object_ids)
                            VALUES
                                (:handoverId, :objectType, :objectLabel, :targetTable,
                                 :targetColumn, :affectedCount, CAST(:objectIds AS JSON))
                            """)
                    .param("handoverId", handoverId)
                    .param("objectType", item.objectType())
                    .param("objectLabel", item.objectLabel())
                    .param("targetTable", item.targetTable())
                    .param("targetColumn", item.targetColumn())
                    .param("affectedCount", item.affectedCount())
                    .param("objectIds", json(item.objectIds()))
                    .update();
        }

        if (disableSource) {
            jdbcClient.sql("UPDATE system_user SET status = 'DISABLED' WHERE user_id = :userId")
                    .param("userId", source.userId())
                    .update();
            // 停用后原账号的既有会话必须失效，否则「已停用」只是列表上的一个字。
            jdbcClient.sql("DELETE FROM auth_refresh_token WHERE user_id = :userId")
                    .param("userId", source.userId())
                    .update();
        }
        return load(handoverId, identity);
    }

    public List<HandoverResponse> list(BootstrapIdentity identity) {
        accessControlService.requireAnyPermission(
                identity, "account handover records require account:handover:read",
                "account:handover:read", "account:handover");
        List<Long> ids = jdbcClient.sql("""
                        SELECT handover_id FROM account_handover
                        ORDER BY handover_id DESC
                        LIMIT 200
                        """)
                .query(Long.class)
                .list();
        return ids.stream().map(id -> load(id, identity)).toList();
    }

    public HandoverResponse load(long handoverId, BootstrapIdentity identity) {
        accessControlService.requireAnyPermission(
                identity, "account handover records require account:handover:read",
                "account:handover:read", "account:handover");
        try {
            HandoverRow row = jdbcClient.sql("""
                            SELECT handover.handover_id, handover.handover_no,
                                   handover.from_user_id, source.display_name AS from_name,
                                   handover.to_user_id, successor.display_name AS to_name,
                                   handover.operator_user_id, operator.display_name AS operator_name,
                                   handover.reason, handover.source_disabled,
                                   handover.transferred_object_count, handover.created_at
                            FROM account_handover handover
                            LEFT JOIN system_user source ON source.user_id = handover.from_user_id
                            LEFT JOIN system_user successor ON successor.user_id = handover.to_user_id
                            LEFT JOIN system_user operator ON operator.user_id = handover.operator_user_id
                            WHERE handover.handover_id = :handoverId
                            """)
                    .param("handoverId", handoverId)
                    .query((rs, rowNum) -> new HandoverRow(
                            rs.getLong("handover_id"), rs.getString("handover_no"),
                            rs.getLong("from_user_id"), rs.getString("from_name"),
                            rs.getLong("to_user_id"), rs.getString("to_name"),
                            rs.getLong("operator_user_id"), rs.getString("operator_name"),
                            rs.getString("reason"), rs.getBoolean("source_disabled"),
                            rs.getInt("transferred_object_count"),
                            rs.getObject("created_at", LocalDateTime.class)))
                    .single();
            List<HandoverItemResponse> items = jdbcClient.sql("""
                            SELECT object_type, object_label, target_table, target_column,
                                   affected_count, object_ids
                            FROM account_handover_item
                            WHERE handover_id = :handoverId
                            ORDER BY handover_item_id
                            """)
                    .param("handoverId", handoverId)
                    .query((rs, rowNum) -> new HandoverItemResponse(
                            rs.getString("object_type"),
                            rs.getString("object_label"),
                            rs.getString("target_table"),
                            rs.getString("target_column"),
                            rs.getInt("affected_count"),
                            readIds(rs.getString("object_ids"))))
                    .list();
            return new HandoverResponse(
                    row.handoverId(), row.handoverNo(),
                    row.fromUserId(), row.fromName(),
                    row.toUserId(), row.toName(),
                    row.operatorUserId(), row.operatorName(),
                    row.reason(), row.sourceDisabled(), row.transferredObjectCount(),
                    row.createdAt(), items);
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "handover record not found", ex);
        }
    }

    // ------------------------------------------------------------------

    /** 按当前数据实际统计每条规则会转走哪些对象。预览与执行走同一份统计，避免两边算得不一样。 */
    private List<HandoverItemResponse> collect(UserRow source) {
        List<HandoverItemResponse> items = new ArrayList<>();
        for (TransferRule rule : AccountHandoverPlan.TRANSFER_RULES) {
            if (!rule.appliesTo(source.userType())) {
                continue;
            }
            List<Long> ids = jdbcClient.sql("""
                            SELECT %s FROM %s
                            WHERE %s = :fromUserId
                              %s
                            ORDER BY %s
                            LIMIT 1000
                            """.formatted(
                            rule.idColumn(), rule.table(), rule.column(),
                            extraCondition(rule), rule.idColumn()))
                    .param("fromUserId", source.userId())
                    .query(Long.class)
                    .list();
            items.add(new HandoverItemResponse(
                    rule.objectType(), rule.label(), rule.table(), rule.column(), ids.size(), ids));
        }
        return items;
    }

    private String extraCondition(TransferRule rule) {
        return rule.extraCondition() == null || rule.extraCondition().isBlank()
                ? ""
                : "AND " + rule.extraCondition();
    }

    private TransferRule ruleOf(String objectType) {
        return AccountHandoverPlan.TRANSFER_RULES.stream()
                .filter(rule -> rule.objectType().equals(objectType))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown transfer rule: " + objectType));
    }

    /** 界面上明确告诉操作人「哪些不会跟着走」，避免以为交接会把绩效也一起转过去。 */
    private List<String> historicalRecordsKept() {
        return List.of(
                "已完成工序与工时记录保留原执行人",
                "入检 / 出检 / 终检记录保留原检验人",
                "返工关闭与责任判定保留原责任人",
                "订单状态流转、沟通消息与审核留痕保留原操作人",
                "奖惩与绩效归属保留原员工");
    }

    private void validatePair(UserRow source, UserRow successor, BootstrapIdentity identity) {
        if (Objects.equals(source.userId(), successor.userId())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "successor must be a different account");
        }
        if (!"ACTIVE".equals(successor.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "successor account must be active");
        }
        // 入口角色必须一致：把医生的病例转给客服，接手的人进不了医生端，
        // 数据还会落在他看不见的数据范围外——转了等于丢了。
        if (!Objects.equals(source.userType(), successor.userType())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "successor must use the same portal role as the source account");
        }
        // 医生之间还要同诊所：跨诊所转病例等于把患者资料交给了另一家客户。
        if ("DOCTOR".equals(source.userType())
                && !Objects.equals(source.clinicId(), successor.clinicId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "doctor handover must stay within the same clinic");
        }
        requireSameDeptScope(identity, source.deptId());
        requireSameDeptScope(identity, successor.deptId());
    }

    /** 与 C 批次 {@code RbacAdminService} 同一套跨部门边界，不另立一套口径。 */
    private void requireSameDeptScope(BootstrapIdentity identity, Long targetDeptId) {
        if (identity.hasPermission("rbac:cross-dept")) {
            return;
        }
        Long actorDeptId = identity.userId() == null ? null : jdbcClient
                .sql("SELECT dept_id FROM system_user WHERE user_id = :userId")
                .param("userId", identity.userId())
                .query(Long.class)
                .optional()
                .orElse(null);
        if (actorDeptId == null || targetDeptId == null || !actorDeptId.equals(targetDeptId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "cross-department handover requires rbac:cross-dept");
        }
    }

    private UserRow loadUser(long userId) {
        return queryUser(userId, false);
    }

    private UserRow lockUser(long userId) {
        return queryUser(userId, true);
    }

    private UserRow queryUser(long userId, boolean lock) {
        try {
            return jdbcClient.sql("""
                            SELECT user_id, username, display_name, user_type, status, clinic_id, dept_id
                            FROM system_user
                            WHERE user_id = :userId
                            %s
                            """.formatted(lock ? "FOR UPDATE" : ""))
                    .param("userId", userId)
                    .query((rs, rowNum) -> new UserRow(
                            rs.getLong("user_id"),
                            rs.getString("username"),
                            rs.getString("display_name"),
                            rs.getString("user_type"),
                            rs.getString("status"),
                            rs.getObject("clinic_id", Long.class),
                            rs.getObject("dept_id", Long.class)))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found", ex);
        }
    }

    private String nextHandoverNo() {
        return "HO" + BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "failed to serialize handover object ids", ex);
        }
    }

    private List<Long> readIds(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return List.of(objectMapper.readValue(value, Long[].class));
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "stored handover object ids are invalid", ex);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record UserRow(
            long userId,
            String username,
            String displayName,
            String userType,
            String status,
            Long clinicId,
            Long deptId) {
    }

    private record HandoverRow(
            long handoverId,
            String handoverNo,
            long fromUserId,
            String fromName,
            long toUserId,
            String toName,
            long operatorUserId,
            String operatorName,
            String reason,
            boolean sourceDisabled,
            int transferredObjectCount,
            LocalDateTime createdAt) {
    }
}
