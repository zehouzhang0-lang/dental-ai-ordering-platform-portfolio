package com.yuri.aiorder.workflow.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.BusinessTime;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.AccessControlService;
import com.yuri.aiorder.design.DesignTaskService;
import com.yuri.aiorder.notification.NotificationPushService;
import com.yuri.aiorder.order.status.ExternalOrderStatus;
import com.yuri.aiorder.order.status.InternalOrderStatus;
import com.yuri.aiorder.order.status.OrderStatusService;
import com.yuri.aiorder.workflow.standardtime.WorkflowStandardTimeProperties;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkflowRuntimeService {

    private static final ZoneId BUSINESS_ZONE = BusinessTime.ZONE;

    private static final String DESIGN_START_ALLOWED_SQL = """
            (
                EXISTS (
                    SELECT 1
                    FROM order_process_node started_node
                    WHERE started_node.instance_id = n.instance_id
                      AND started_node.started_at IS NOT NULL
                )
                OR EXISTS (
                    SELECT 1
                    FROM design_task confirmed_task
                    WHERE confirmed_task.order_id = i.order_id
                      AND confirmed_task.task_status = 'DOCTOR_CONFIRMED'
                )
                OR (
                    NOT EXISTS (
                        SELECT 1
                        FROM design_task any_task
                        WHERE any_task.order_id = i.order_id
                    )
                    AND COALESCE(
                        (
                            SELECT latest_draft.draft_status
                            FROM design_draft latest_draft
                            WHERE latest_draft.order_id = i.order_id
                            ORDER BY latest_draft.version_no DESC, latest_draft.design_draft_id DESC
                            LIMIT 1
                        ),
                        'DOCTOR_CONFIRMED'
                    ) = 'DOCTOR_CONFIRMED'
                )
            )
            """;

    private static final List<String> PRODUCTION_KANBAN_STAGES = List.of(
            "CAD审核/扫描", "石膏", "CAD设计", "CAM排版/染色/切削", "车瓷", "车金", "上瓷",
            "排牙", "蜡型", "充胶完成", "钢托打磨/就位", "胶托打磨/就位", "质检", "外发加工");

    private static final Map<String, String> CS_BUSINESS_GATE_CATEGORIES = Map.of(
            "客服定基台", "REVIEW",
            "客服核对订单信息及账单", "BILLING");

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final OrderStatusService orderStatusService;
    private final AccessControlService accessControlService;
    private final DesignTaskService designTaskService;
    private final NotificationPushService notificationPushService;
    private final WorkflowStandardTimeProperties standardTimeProperties;

    public WorkflowRuntimeService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            OrderStatusService orderStatusService,
            AccessControlService accessControlService,
            DesignTaskService designTaskService,
            NotificationPushService notificationPushService,
            WorkflowStandardTimeProperties standardTimeProperties) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.orderStatusService = orderStatusService;
        this.accessControlService = accessControlService;
        this.designTaskService = designTaskService;
        this.notificationPushService = notificationPushService;
        this.standardTimeProperties = standardTimeProperties;
    }

    @Transactional
    public ProductionReviewResponse reviewProduction(
            long orderId, ProductionReviewRequest request, BootstrapIdentity identity) {
        accessControlService.requireProductionReview(identity);
        requirePendingProductionReview(orderId);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "production review request is required");
        }
        String action = normalize(request.action());
        if ("REJECT".equals(action)) {
            String rejectReason = normalizeText(request.rejectReason());
            if (rejectReason == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "reject_reason is required when rejecting production review");
            }
            jdbcClient.sql("""
                            UPDATE orders
                            SET reject_reason = :rejectReason
                            WHERE order_id = :orderId
                            """)
                    .param("rejectReason", rejectReason)
                    .param("orderId", orderId)
                    .update();
            ExternalOrderStatus external = orderStatusService.updateOrderState(
                    orderId, InternalOrderStatus.PRODUCTION_REJECTED, "PRODUCTION_REJECT", identity.userId(), rejectReason);
            return new ProductionReviewResponse(orderId, null, InternalOrderStatus.PRODUCTION_REJECTED.name(), external.name());
        }
        if (!"APPROVE".equals(action)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported production review action");
        }
        ProductionReviewRequest normalizedRequest = normalizeApprovalRequest(orderId, request);
        long instanceId = instantiateIfAbsent(orderId, normalizedRequest);
        designTaskService.ensureTaskForOrder(orderId, designGateNodeId(instanceId), identity);
        ExternalOrderStatus external = orderStatusService.updateOrderState(
                orderId,
                InternalOrderStatus.IN_DESIGN,
                "PRODUCTION_REVIEW_APPROVE",
                identity.userId(),
                "process instance and design task created");
        return new ProductionReviewResponse(
                orderId, instanceId, InternalOrderStatus.IN_DESIGN.name(), external.name());
    }

    private void requirePendingProductionReview(long orderId) {
        String currentStatus;
        try {
            currentStatus = jdbcClient.sql("""
                            SELECT internal_status
                            FROM orders
                            WHERE order_id = :orderId
                            FOR UPDATE
                            """)
                    .param("orderId", orderId)
                    .query(String.class)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found", ex);
        }
        if (!InternalOrderStatus.PENDING_PRODUCTION_REVIEW.name().equals(currentStatus)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "order is not pending production review");
        }
    }

    public ProcessInstanceResponse getProcessInstance(long orderId, BootstrapIdentity identity) {
        accessControlService.requireInternalAccess(identity, "doctor cannot access process instance");
        InstanceRow instance = loadInstanceByOrder(orderId, identity);
        return new ProcessInstanceResponse(
                instance.instanceId(),
                instance.orderId(),
                instance.instanceStatus(),
                instance.intakeBranchUsed(),
                instance.createdAt(),
                instance.updatedAt(),
                loadNodes(instance.instanceId()),
                loadEdges(instance.instanceId()));
    }

    public ProductionKanbanSummaryResponse getProductionKanbanSummary(LocalDate date, BootstrapIdentity identity) {
        accessControlService.requireInternalAccess(identity, "doctor cannot access production kanban");
        String dataScope = accessControlService.effectiveDataScope(identity);
        accessControlService.requireScopedIdentity(identity, dataScope);
        LocalDateTime startAt = date.atStartOfDay();
        LocalDateTime endExclusive = date.plusDays(1).atStartOfDay();
        LocalDateTime asOf = date.equals(LocalDate.now(BUSINESS_ZONE))
                ? currentDatabaseTime()
                : endExclusive;
        Map<String, StageMetricAccumulator> stages = new LinkedHashMap<>();
        for (String stage : PRODUCTION_KANBAN_STAGES) {
            stages.put(stage, new StageMetricAccumulator());
        }
        Set<Long> visibleOrderIds = new LinkedHashSet<>();
        loadNodeKanbanMetrics(
                stages,
                visibleOrderIds,
                startAt,
                endExclusive,
                asOf,
                identity,
                dataScope);
        loadQuestionKanbanMetrics(stages, endExclusive, asOf, identity, dataScope);
        loadReworkKanbanMetrics(stages, endExclusive, asOf, identity, dataScope);
        List<ProductionKanbanStageSummaryResponse> result = new ArrayList<>();
        for (Map.Entry<String, StageMetricAccumulator> entry : stages.entrySet()) {
            StageMetricAccumulator metric = entry.getValue();
            result.add(new ProductionKanbanStageSummaryResponse(
                    entry.getKey(), metric.unfinished, metric.inProgress, metric.completed, metric.overdue,
                    metric.pendingQuestions, metric.internalReworks));
        }
        return new ProductionKanbanSummaryResponse(date, visibleOrderIds.stream().toList(), result);
    }

    private LocalDateTime currentDatabaseTime() {
        return jdbcClient.sql("SELECT CURRENT_TIMESTAMP(3)")
                .query(LocalDateTime.class)
                .single();
    }

    @Transactional
    public ProductionQuestionResponse createProductionQuestion(
            long nodeInstanceId, ProductionQuestionRequest request, BootstrapIdentity identity) {
        NodeRow node = lockNode(nodeInstanceId);
        requireWorkerAssignment(node, identity);
        jdbcClient.sql("""
                        INSERT INTO production_question
                            (order_id, node_instance_id, content, asked_by_user_id, status)
                        VALUES (:orderId, :nodeInstanceId, :content, :askedByUserId, 'OPEN')
                        """)
                .param("orderId", node.orderId())
                .param("nodeInstanceId", nodeInstanceId)
                .param("content", request.content().trim())
                .param("askedByUserId", identity.userId())
                .update();
        return loadProductionQuestion(lastInsertId());
    }

    @Transactional
    public ProductionQuestionResponse resolveProductionQuestion(
            long questionId, ProductionQuestionRequest request, BootstrapIdentity identity) {
        QuestionScope question = lockProductionQuestion(questionId);
        NodeRow node = lockNode(question.nodeInstanceId());
        requireWorkerAssignment(node, identity);
        jdbcClient.sql("""
                        UPDATE production_question
                        SET status = 'RESOLVED',
                            resolved_by_user_id = :resolvedByUserId,
                            resolved_at = CURRENT_TIMESTAMP(3),
                            resolution_note = :resolutionNote
                        WHERE question_id = :questionId
                          AND status = 'OPEN'
                        """)
                .param("resolvedByUserId", identity.userId())
                .param("resolutionNote", request.content().trim())
                .param("questionId", questionId)
                .update();
        return loadProductionQuestion(questionId);
    }

    @Transactional
    public void assign(long orderId, AssignmentRequest request, BootstrapIdentity identity) {
        accessControlService.requireProcessManagement(identity);
        ensureInstanceForOrder(orderId);
        if (request.assignments() == null || request.assignments().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "assignments is required");
        }
        for (AssignmentRequest.AssignmentItem item : request.assignments()) {
            NodeRow node = lockNode(item.nodeInstanceId());
            requireAssignableNode(orderId, node);
            if (node.assignedUserId() != null) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "assigned node must use the reassign endpoint");
            }
            requireActiveWorker(item.userId());
            int updated = jdbcClient.sql("""
                            UPDATE order_process_node n
                            JOIN order_process_instance i ON i.instance_id = n.instance_id
                            SET n.assigned_user_id = :userId
                            WHERE i.order_id = :orderId
                              AND n.node_instance_id = :nodeInstanceId
                              AND n.node_status IN ('PENDING', 'READY', 'IN_PROGRESS')
                              AND n.assigned_user_id IS NULL
                            """)
                    .param("userId", item.userId())
                    .param("orderId", orderId)
                    .param("nodeInstanceId", item.nodeInstanceId())
                    .update();
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "node cannot be assigned");
            }
            recordAssignmentEvent(orderId, node, "ASSIGNED", item.userId(), null, identity);
            emitAssignmentNotification(orderId, node, item.userId(), "TASK_ASSIGNED", "你有新的生产工序任务");
        }
    }

    @Transactional
    public void reassign(long orderId, long nodeInstanceId, ReassignRequest request, BootstrapIdentity identity) {
        accessControlService.requireProcessManagement(identity);
        ensureInstanceForOrder(orderId);
        NodeRow node = lockNode(nodeInstanceId);
        requireAssignableNode(orderId, node);
        requireActiveWorker(request.newUserId());
        String reason = normalizeText(request.reason());
        if (reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason is required when reassigning a node");
        }
        if (node.assignedUserId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "unassigned node must use the assign endpoint");
        }
        if (node.assignedUserId() == request.newUserId()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "new worker must differ from current worker");
        }
        int updated = jdbcClient.sql("""
                        UPDATE order_process_node n
                        JOIN order_process_instance i ON i.instance_id = n.instance_id
                        SET n.assigned_user_id = :userId
                        WHERE i.order_id = :orderId
                          AND n.node_instance_id = :nodeInstanceId
                          AND n.node_status IN ('PENDING', 'READY', 'IN_PROGRESS')
                        """)
                .param("userId", request.newUserId())
                .param("orderId", orderId)
                .param("nodeInstanceId", nodeInstanceId)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "node cannot be reassigned");
        }
        recordAssignmentEvent(orderId, node, "REASSIGNED", request.newUserId(), reason, identity);
        emitAssignmentNotification(orderId, node, request.newUserId(), "TASK_REASSIGNED", "生产工序任务已转派给你");
    }

    @Transactional
    public NodeActionResponse startNode(long nodeInstanceId, BootstrapIdentity identity) {
        NodeRow node = lockNode(nodeInstanceId);
        requireWorkerAssignment(node, identity);
        if (!"READY".equals(node.nodeStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "node is not ready to start");
        }
        if (node.needInCheck() == 1 && !hasPassedCheck(nodeInstanceId, "IN")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "node must pass in-check before start");
        }
        requireDoctorConfirmedDesignBeforeProductionStart(node);
        jdbcClient.sql("""
                        UPDATE order_process_node
                        SET node_status = 'IN_PROGRESS',
                            started_at = COALESCE(started_at, CURRENT_TIMESTAMP(3)),
                            deadline_at = CASE
                                WHEN :formalStandardTimeEnabled
                                THEN COALESCE(
                                    deadline_at,
                                    DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL standard_duration MINUTE)
                                )
                                ELSE NULL
                            END
                        WHERE node_instance_id = :nodeInstanceId
                        """)
                .param("formalStandardTimeEnabled", standardTimeProperties.formalEnabled())
                .param("nodeInstanceId", nodeInstanceId)
                .update();
        orderStatusService.updateOrderState(
                node.orderId(), InternalOrderStatus.IN_PRODUCTION, "PROCESS_NODE_START", identity.userId(), node.processName());
        return new NodeActionResponse(nodeInstanceId, "IN_PROGRESS");
    }

    @Transactional
    public NodeActionResponse completeNode(long nodeInstanceId, BootstrapIdentity identity) {
        NodeRow node = lockNode(nodeInstanceId);
        requireWorkerAssignment(node, identity);
        if (!"IN_PROGRESS".equals(node.nodeStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "node is not in progress");
        }
        jdbcClient.sql("""
                        UPDATE order_process_node
                        SET node_status = 'COMPLETED',
                            completed_at = CURRENT_TIMESTAMP(3)
                        WHERE node_instance_id = :nodeInstanceId
                        """)
                .param("nodeInstanceId", nodeInstanceId)
                .update();
        if (node.needOutCheck() == 0) {
            activateReadyNodes(node.instanceId());
        }
        completeInstanceIfDone(node.instanceId());
        return new NodeActionResponse(nodeInstanceId, "COMPLETED");
    }

    @Transactional
    public NodeActionResponse completeBusinessGate(
            long orderId,
            long nodeInstanceId,
            BusinessGateActionRequest request,
            BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity,
                "workflow:operate-business-gate",
                "business gate completion requires workflow:operate-business-gate");
        NodeRow node = lockNode(nodeInstanceId);
        if (node.orderId() != orderId) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "process node does not belong to this order");
        }
        String expectedCategory = CS_BUSINESS_GATE_CATEGORIES.get(node.processName());
        if (expectedCategory == null || !expectedCategory.equalsIgnoreCase(node.nodeCategory())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "only an approved CS business gate can use this action");
        }
        if (!"READY".equals(node.nodeStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "business gate is not ready");
        }
        if ("客服核对订单信息及账单".equals(node.processName()) && !hasUploadedBill(orderId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "an uploaded bill is required before completing the billing business gate");
        }
        String note = normalizeText(request.note());
        if (note == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "business gate note is required");
        }
        int updated = jdbcClient.sql("""
                        UPDATE order_process_node
                        SET node_status = 'COMPLETED',
                            need_in_check = 0,
                            need_out_check = 0,
                            started_at = COALESCE(started_at, CURRENT_TIMESTAMP(3)),
                            completed_at = CURRENT_TIMESTAMP(3)
                        WHERE node_instance_id = :nodeInstanceId
                          AND node_status = 'READY'
                        """)
                .param("nodeInstanceId", nodeInstanceId)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "business gate status changed, please refresh");
        }
        jdbcClient.sql("""
                        INSERT INTO workflow_business_gate_audit
                            (order_id, node_instance_id, process_name, node_category, action_type,
                             action_note, actor_user_id, actor_role, before_status, after_status)
                        VALUES
                            (:orderId, :nodeInstanceId, :processName, :nodeCategory, 'COMPLETE',
                             :actionNote, :actorUserId, :actorRole, :beforeStatus, 'COMPLETED')
                        """)
                .param("orderId", orderId)
                .param("nodeInstanceId", nodeInstanceId)
                .param("processName", node.processName())
                .param("nodeCategory", node.nodeCategory())
                .param("actionNote", note)
                .param("actorUserId", identity.userId())
                .param("actorRole", identity.role().name())
                .param("beforeStatus", node.nodeStatus())
                .update();
        activateReadyNodes(node.instanceId());
        completeInstanceIfDone(node.instanceId());
        return new NodeActionResponse(nodeInstanceId, "COMPLETED");
    }

    private boolean hasUploadedBill(long orderId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_bill
                        WHERE order_id = :orderId
                          AND bill_status = 'UPLOADED'
                          AND file_id IS NOT NULL
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single() > 0;
    }

    @Transactional
    public void activateAfterPassedOutCheck(long nodeInstanceId) {
        NodeRow node = lockNode(nodeInstanceId);
        if (node.needOutCheck() != 1 || !hasPassedCheck(nodeInstanceId, "OUT")) {
            return;
        }
        activateReadyNodes(node.instanceId());
        completeInstanceIfDone(node.instanceId());
    }

    @Transactional
    public NodeActionResponse skipNode(long nodeInstanceId, SkipNodeRequest request, BootstrapIdentity identity) {
        NodeRow node = lockNode(nodeInstanceId);
        if (identity.role() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only ADMIN can skip an optional node");
        }
        if (node.isOptional() != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only optional nodes can be skipped");
        }
        if (!List.of("PENDING", "READY").contains(node.nodeStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "node cannot be skipped in current status");
        }
        jdbcClient.sql("""
                        UPDATE order_process_node
                        SET node_status = 'SKIPPED',
                            skipped_at = CURRENT_TIMESTAMP(3),
                            skip_reason = :reason
                        WHERE node_instance_id = :nodeInstanceId
                        """)
                .param("reason", request.reason().trim())
                .param("nodeInstanceId", nodeInstanceId)
                .update();
        activateReadyNodes(node.instanceId());
        completeInstanceIfDone(node.instanceId());
        return new NodeActionResponse(nodeInstanceId, "SKIPPED");
    }

    public List<MyTaskResponse> getMyTasks(BootstrapIdentity identity, String status, boolean finalOnly) {
        accessControlService.requirePermission(
                identity, "workflow:operate-assigned", "tasks/mine requires workflow:operate-assigned");
        if (identity.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "worker user id is required");
        }
        String normalizedStatus = status == null || status.isBlank() ? null : normalize(status);
        String finalNodeClause = finalOnly
                ? """
                          AND n.node_instance_id = (
                              SELECT last_node.node_instance_id
                              FROM order_process_node last_node
                              JOIN order_process_instance last_instance ON last_instance.instance_id = last_node.instance_id
                              WHERE last_instance.order_id = i.order_id
                              ORDER BY last_node.step_order DESC, last_node.node_instance_id DESC
                              LIMIT 1
                          )
                        """
                : "";
        return jdbcClient.sql("""
                        SELECT
                            n.node_instance_id,
                            i.order_id,
                            o.order_no,
                            n.process_name,
                            n.node_status,
                            CASE
                                WHEN :formalStandardTimeEnabled THEN n.standard_duration
                                ELSE NULL
                            END AS standard_duration,
                            CASE
                                WHEN n.node_status = 'READY'
                                     AND (n.need_in_check = 0 OR EXISTS (
                                         SELECT 1
                                         FROM check_record in_check
                                         WHERE in_check.node_instance_id = n.node_instance_id
                                           AND in_check.check_type = 'IN'
                                           AND in_check.result = 'PASS'
                                     ))
                                     AND %s
                                THEN TRUE
                                ELSE FALSE
                            END AS can_start,
                            CASE
                                WHEN n.node_status = 'READY'
                                     AND n.need_in_check = 1
                                     AND NOT EXISTS (
                                         SELECT 1
                                         FROM check_record in_check
                                         WHERE in_check.node_instance_id = n.node_instance_id
                                           AND in_check.check_type = 'IN'
                                           AND in_check.result = 'PASS'
                                     )
                                THEN 'IN_CHECK_REQUIRED'
                                WHEN n.node_status = 'READY'
                                     AND NOT %s
                                THEN 'DESIGN_CONFIRMATION_REQUIRED'
                                ELSE NULL
                            END AS start_block_reason
                        FROM order_process_node n
                        JOIN order_process_instance i ON i.instance_id = n.instance_id
                        JOIN orders o ON o.order_id = i.order_id
                        WHERE n.assigned_user_id = :userId
                          AND (:status IS NULL OR n.node_status = :status)
                        %s
                        ORDER BY n.updated_at DESC, n.node_instance_id DESC
                        """.formatted(DESIGN_START_ALLOWED_SQL, DESIGN_START_ALLOWED_SQL, finalNodeClause))
                .param("userId", identity.userId())
                .param("status", normalizedStatus)
                .param("formalStandardTimeEnabled", standardTimeProperties.formalEnabled())
                .query((rs, rowNum) -> new MyTaskResponse(
                        rs.getLong("node_instance_id"),
                        rs.getLong("order_id"),
                        rs.getString("order_no"),
                        rs.getString("process_name"),
                        rs.getString("node_status"),
                        rs.getObject("standard_duration", Integer.class),
                        rs.getBoolean("can_start"),
                        rs.getString("start_block_reason")))
                .list();
    }

    private long instantiateIfAbsent(long orderId, ProductionReviewRequest request) {
        Long existing = jdbcClient.sql("""
                        SELECT instance_id
                        FROM order_process_instance
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        ChainRow chain = resolveChainForOrder(orderId, request.chainId());
        String branchParams = branchParamsJson(request);
        jdbcClient.sql("""
                        INSERT INTO order_process_instance
                            (order_id, chain_id, chain_version, intake_branch_used, branch_params, instance_status)
                        VALUES
                            (:orderId, :chainId, :chainVersion, :intakeBranch, CAST(:branchParams AS JSON), 'ACTIVE')
                        """)
                .param("orderId", orderId)
                .param("chainId", chain.chainId())
                .param("chainVersion", chain.version())
                .param("intakeBranch", request.intakeBranch())
                .param("branchParams", branchParams)
                .update();
        long instanceId = jdbcClient.sql("SELECT instance_id FROM order_process_instance WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        ensureDesignGateDefinition(chain.chainId());
        copyNodes(instanceId, chain.chainId(), request);
        copyEdges(instanceId, chain.chainId());
        connectDesignGateToRouteRoots(instanceId);
        activateReadyNodes(instanceId);
        return instanceId;
    }

    private void ensureDesignGateDefinition(long chainId) {
        jdbcClient.sql("""
                        INSERT INTO workflow_node
                            (chain_id, node_code, process_name, stage_name, step_order, is_optional,
                             branch_group, branch_key, standard_duration, default_role, node_category,
                             need_in_check, need_out_check)
                        SELECT
                            chain.chain_id,
                            CONCAT(chain.chain_code, '_DESIGN_CONFIRMATION_GATE'),
                            '设计稿确认',
                            '设计审核',
                            -10,
                            0,
                            NULL,
                            NULL,
                            NULL,
                            'WORKER',
                            'DESIGN_GATE',
                            0,
                            0
                        FROM workflow_chain chain
                        WHERE chain.chain_id = :chainId
                          AND NOT EXISTS (
                              SELECT 1
                              FROM workflow_node existing
                              WHERE existing.chain_id = chain.chain_id
                                AND existing.node_category = 'DESIGN_GATE'
                          )
                        """)
                .param("chainId", chainId)
                .update();
    }

    private void copyNodes(long instanceId, long chainId, ProductionReviewRequest request) {
        List<DefinitionNode> nodes = jdbcClient.sql("""
                        SELECT
                            node_id,
                            node_code,
                            process_name,
                            stage_name,
                            step_order,
                            is_optional,
                            branch_group,
                            branch_key,
                            CASE
                                WHEN :formalStandardTimeEnabled
                                THEN COALESCE(
                                    (
                                        SELECT standard_item.standard_duration_minutes
                                        FROM workflow_standard_time_item standard_item
                                        JOIN workflow_standard_time_version standard_version
                                          ON standard_version.standard_time_version_id =
                                             standard_item.standard_time_version_id
                                        WHERE standard_item.node_id = workflow_node.node_id
                                          AND standard_item.status = 'ACTIVE'
                                          AND standard_version.publication_status = 'ACTIVE'
                                          AND standard_version.effective_at <= CURRENT_TIMESTAMP(3)
                                        ORDER BY standard_version.effective_at DESC,
                                                 standard_version.version_no DESC
                                        LIMIT 1
                                    ),
                                    workflow_node.standard_duration
                                )
                                ELSE NULL
                            END AS standard_duration,
                            default_role,
                            node_category,
                            need_in_check,
                            need_out_check
                        FROM workflow_node
                        WHERE chain_id = :chainId
                        ORDER BY step_order, node_id
                        """)
                .param("chainId", chainId)
                .param("formalStandardTimeEnabled", standardTimeProperties.formalEnabled())
                .query((rs, rowNum) -> new DefinitionNode(
                        rs.getLong("node_id"),
                        rs.getString("node_code"),
                        rs.getString("process_name"),
                        rs.getString("stage_name"),
                        rs.getInt("step_order"),
                        rs.getInt("is_optional"),
                        rs.getString("branch_group"),
                        rs.getString("branch_key"),
                        rs.getObject("standard_duration", Integer.class),
                        rs.getString("default_role"),
                        rs.getString("node_category"),
                        rs.getInt("need_in_check"),
                        rs.getInt("need_out_check")))
                .list();
        for (DefinitionNode node : nodes) {
            if (!branchMatches(node.branchGroup(), node.branchKey(), request)) {
                continue;
            }
            if ("ORDER_INTAKE".equalsIgnoreCase(node.nodeCategory())
                    || ("REVIEW".equalsIgnoreCase(node.nodeCategory())
                            && "下单入厂".equals(node.stageName()))) {
                continue;
            }
            jdbcClient.sql("""
                            INSERT INTO order_process_node
                                (instance_id, source_node_id, node_code, process_name, stage_name, step_order,
                                 is_optional, branch_group, branch_key, standard_duration, default_role,
                                 node_category, need_in_check, need_out_check, node_status)
                            VALUES
                                (:instanceId, :sourceNodeId, :nodeCode, :processName, :stageName, :stepOrder,
                                 :isOptional, :branchGroup, :branchKey, :standardDuration, :defaultRole,
                                 :nodeCategory, :needInCheck, :needOutCheck, 'PENDING')
                            """)
                    .param("instanceId", instanceId)
                    .param("sourceNodeId", node.nodeId())
                    .param("nodeCode", node.nodeCode())
                    .param("processName", node.processName())
                    .param("stageName", node.stageName())
                    .param("stepOrder", node.stepOrder())
                    .param("isOptional", node.isOptional())
                    .param("branchGroup", node.branchGroup())
                    .param("branchKey", node.branchKey())
                    .param("standardDuration", node.standardDuration())
                    .param("defaultRole", node.defaultRole())
                    .param("nodeCategory", node.nodeCategory())
                    .param("needInCheck", node.needInCheck())
                    .param("needOutCheck", node.needOutCheck())
                    .update();
        }
    }

    private void connectDesignGateToRouteRoots(long instanceId) {
        long gateNodeId = designGateNodeId(instanceId);
        int inserted = jdbcClient.sql("""
                        INSERT INTO order_process_edge
                            (instance_id, from_node_instance_id, to_node_instance_id, edge_type, condition_key)
                        SELECT
                            :instanceId,
                            :gateNodeId,
                            candidate.node_instance_id,
                            'SEQUENCE',
                            'DESIGN_DOCTOR_CONFIRMED'
                        FROM order_process_node candidate
                        WHERE candidate.instance_id = :instanceId
                          AND candidate.node_instance_id <> :gateNodeId
                          AND NOT EXISTS (
                              SELECT 1
                              FROM order_process_edge incoming
                              WHERE incoming.instance_id = candidate.instance_id
                                AND incoming.to_node_instance_id = candidate.node_instance_id
                          )
                        """)
                .param("instanceId", instanceId)
                .param("gateNodeId", gateNodeId)
                .update();
        if (inserted == 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "workflow route has no executable node after design confirmation");
        }
    }

    private long designGateNodeId(long instanceId) {
        return jdbcClient.sql("""
                        SELECT node_instance_id
                        FROM order_process_node
                        WHERE instance_id = :instanceId
                          AND node_category = 'DESIGN_GATE'
                        ORDER BY step_order, node_instance_id
                        LIMIT 1
                        """)
                .param("instanceId", instanceId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "workflow chain is missing the design confirmation gate"));
    }

    private void copyEdges(long instanceId, long chainId) {
        Map<Long, Long> instanceNodeBySourceId = new LinkedHashMap<>();
        jdbcClient.sql("""
                        SELECT source_node_id, node_instance_id
                        FROM order_process_node
                        WHERE instance_id = :instanceId
                        ORDER BY step_order, node_instance_id
                        """)
                .param("instanceId", instanceId)
                .query((rs, rowNum) -> new InstanceNode(
                        rs.getLong("source_node_id"),
                        rs.getLong("node_instance_id")))
                .list()
                .forEach(node -> instanceNodeBySourceId.put(node.sourceNodeId(), node.nodeInstanceId()));

        Map<Long, List<DefinitionEdge>> outgoingEdges = new LinkedHashMap<>();
        jdbcClient.sql("""
                        SELECT from_node_id, to_node_id, edge_type, condition_key
                        FROM workflow_edge
                        WHERE chain_id = :chainId
                        ORDER BY edge_id
                        """)
                .param("chainId", chainId)
                .query((rs, rowNum) -> new DefinitionEdge(
                        rs.getLong("from_node_id"),
                        rs.getLong("to_node_id"),
                        rs.getString("edge_type"),
                        rs.getString("condition_key")))
                .list()
                .forEach(edge -> outgoingEdges
                        .computeIfAbsent(edge.fromNodeId(), ignored -> new ArrayList<>())
                        .add(edge));

        Map<String, ProjectedEdge> projectedEdges = new LinkedHashMap<>();
        for (Map.Entry<Long, Long> selectedSource : instanceNodeBySourceId.entrySet()) {
            List<Long> traversal = new ArrayList<>();
            traversal.add(selectedSource.getKey());
            Set<Long> visitedOmittedNodes = new LinkedHashSet<>();
            for (int cursor = 0; cursor < traversal.size(); cursor++) {
                for (DefinitionEdge edge : outgoingEdges.getOrDefault(traversal.get(cursor), List.of())) {
                    Long targetInstanceId = instanceNodeBySourceId.get(edge.toNodeId());
                    if (targetInstanceId != null) {
                        if (!targetInstanceId.equals(selectedSource.getValue())) {
                            String key = selectedSource.getValue() + ":" + targetInstanceId + ":" + edge.edgeType();
                            projectedEdges.putIfAbsent(key, new ProjectedEdge(
                                    selectedSource.getValue(),
                                    targetInstanceId,
                                    edge.edgeType(),
                                    edge.conditionKey()));
                        }
                    } else if (visitedOmittedNodes.add(edge.toNodeId())) {
                        traversal.add(edge.toNodeId());
                    }
                }
            }
        }

        for (ProjectedEdge edge : projectedEdges.values()) {
            jdbcClient.sql("""
                            INSERT INTO order_process_edge
                                (instance_id, from_node_instance_id, to_node_instance_id, edge_type, condition_key)
                            VALUES
                                (:instanceId, :fromNodeInstanceId, :toNodeInstanceId, :edgeType, :conditionKey)
                            """)
                    .param("instanceId", instanceId)
                    .param("fromNodeInstanceId", edge.fromNodeInstanceId())
                    .param("toNodeInstanceId", edge.toNodeInstanceId())
                    .param("edgeType", edge.edgeType())
                    .param("conditionKey", edge.conditionKey())
                    .update();
        }
    }

    private void activateReadyNodes(long instanceId) {
        jdbcClient.sql("""
                        UPDATE order_process_node target
                        JOIN (
                            SELECT ready_nodes.node_instance_id
                            FROM (
                                SELECT candidate.node_instance_id
                                FROM order_process_node candidate
                                WHERE candidate.instance_id = :instanceId
                                  AND candidate.node_status = 'PENDING'
                                  AND NOT EXISTS (
                                      SELECT 1
                                      FROM order_process_edge incoming
                                      JOIN order_process_node predecessor
                                        ON predecessor.node_instance_id = incoming.from_node_instance_id
                                      WHERE incoming.instance_id = candidate.instance_id
                                        AND incoming.to_node_instance_id = candidate.node_instance_id
                                        AND (
                                            predecessor.node_status NOT IN ('COMPLETED', 'SKIPPED')
                                            OR (
                                                predecessor.need_out_check = 1
                                                AND NOT EXISTS (
                                                    SELECT 1
                                                    FROM check_record out_check
                                                    WHERE out_check.node_instance_id = predecessor.node_instance_id
                                                      AND out_check.check_type = 'OUT'
                                                      AND out_check.result = 'PASS'
                                                )
                                            )
                                        )
                                  )
                            ) ready_nodes
                        ) selected ON selected.node_instance_id = target.node_instance_id
                        SET target.node_status = 'READY'
                        """)
                .param("instanceId", instanceId)
                .update();
    }

    private void completeInstanceIfDone(long instanceId) {
        long openCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_process_node
                        WHERE instance_id = :instanceId
                          AND (
                              node_status NOT IN ('COMPLETED', 'SKIPPED')
                              OR (
                                  need_out_check = 1
                                  AND NOT EXISTS (
                                      SELECT 1
                                      FROM check_record out_check
                                      WHERE out_check.node_instance_id = order_process_node.node_instance_id
                                        AND out_check.check_type = 'OUT'
                                        AND out_check.result = 'PASS'
                                  )
                              )
                          )
                        """)
                .param("instanceId", instanceId)
                .query(Long.class)
                .single();
        if (openCount == 0) {
            jdbcClient.sql("""
                            UPDATE order_process_instance
                            SET instance_status = 'COMPLETED',
                                updated_at = CURRENT_TIMESTAMP(3)
                            WHERE instance_id = :instanceId
                            """)
                    .param("instanceId", instanceId)
                    .update();
        }
    }

    private boolean branchMatches(String branchGroup, String branchKey, ProductionReviewRequest request) {
        if (branchGroup == null || branchGroup.isBlank() || branchKey == null || branchKey.isBlank()) {
            return true;
        }
        if ("intake".equalsIgnoreCase(branchGroup) && request.intakeBranch() != null) {
            return branchKey.equalsIgnoreCase(request.intakeBranch());
        }
        JsonNode params = request.branchParams();
        if (params == null || !params.has(branchGroup)) {
            return false;
        }
        return branchKey.equalsIgnoreCase(params.path(branchGroup).asText());
    }

    private ProductionReviewRequest normalizeApprovalRequest(long orderId, ProductionReviewRequest request) {
        ChainRow chain = resolveChainForOrder(orderId, request.chainId());
        String configuredIntake = normalize(chain.intakeBranch());
        String requestedIntake = normalizeText(request.intakeBranch());
        if (requestedIntake != null) {
            requestedIntake = normalize(requestedIntake);
        }
        String effectiveIntake;
        if ("BOTH".equals(configuredIntake)) {
            if (requestedIntake == null || !Set.of("SCAN", "IMPRESSION").contains(requestedIntake)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "intake_branch must be SCAN or IMPRESSION for a dual-intake workflow");
            }
            effectiveIntake = requestedIntake;
        } else if ("NONE".equals(configuredIntake)) {
            if (requestedIntake != null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "intake_branch must be omitted for a workflow without an intake choice");
            }
            effectiveIntake = null;
        } else {
            if (configuredIntake == null || !Set.of("SCAN", "IMPRESSION").contains(configuredIntake)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "workflow chain has an invalid intake_branch");
            }
            if (requestedIntake != null && !configuredIntake.equals(requestedIntake)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "intake_branch does not match the selected workflow chain");
            }
            effectiveIntake = configuredIntake;
        }

        JsonNode branchParams = request.branchParams();
        if (branchParams != null && !branchParams.isNull() && !branchParams.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "branch_params must be an object");
        }
        Map<String, Set<String>> optionsByGroup = new LinkedHashMap<>();
        jdbcClient.sql("""
                        SELECT branch_group, branch_key
                        FROM workflow_node
                        WHERE chain_id = :chainId
                          AND branch_group IS NOT NULL
                          AND branch_group <> ''
                          AND branch_key IS NOT NULL
                          AND branch_key <> ''
                          AND LOWER(branch_group) <> 'intake'
                        ORDER BY step_order, node_id
                        """)
                .param("chainId", chain.chainId())
                .query((rs, rowNum) -> Map.entry(
                        rs.getString("branch_group"),
                        normalize(rs.getString("branch_key"))))
                .list()
                .forEach(option -> optionsByGroup
                        .computeIfAbsent(option.getKey(), ignored -> new LinkedHashSet<>())
                        .add(option.getValue()));
        for (Map.Entry<String, Set<String>> group : optionsByGroup.entrySet()) {
            String selected = branchParams == null || !branchParams.hasNonNull(group.getKey())
                    ? null
                    : normalizeText(branchParams.path(group.getKey()).asText());
            String normalizedSelection = selected == null ? null : normalize(selected);
            if (normalizedSelection == null || !group.getValue().contains(normalizedSelection)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "branch_params." + group.getKey() + " must select one of " + group.getValue());
            }
        }
        return new ProductionReviewRequest(
                "APPROVE", chain.chainId(), effectiveIntake, branchParams, null);
    }

    private void loadNodeKanbanMetrics(
            Map<String, StageMetricAccumulator> stages,
            Set<Long> visibleOrderIds,
            LocalDateTime startAt,
            LocalDateTime endExclusive,
            LocalDateTime asOf,
            BootstrapIdentity identity,
            String dataScope) {
        jdbcClient.sql("""
                        WITH unfinished_candidates AS (
                            SELECT
                                i.order_id,
                                COALESCE(n.stage_name, '') AS stage_name,
                                n.process_name,
                                o.internal_status,
                                CASE
                                    WHEN n.started_at IS NOT NULL AND n.started_at <= :asOf THEN 1
                                    ELSE 0
                                END AS in_progress,
                                CASE
                                    WHEN :formalStandardTimeEnabled
                                         AND n.deadline_at IS NOT NULL
                                         AND n.deadline_at < :asOf THEN 1
                                    ELSE 0
                                END AS overdue,
                                ROW_NUMBER() OVER (
                                    PARTITION BY i.order_id
                                    ORDER BY
                                        CASE
                                            WHEN n.started_at IS NOT NULL AND n.started_at <= :asOf THEN 0
                                            ELSE 1
                                        END,
                                        n.step_order,
                                        n.node_instance_id
                                ) AS row_num
                            FROM order_process_instance i
                            JOIN order_process_node n ON n.instance_id = i.instance_id
                            JOIN orders o ON o.order_id = i.order_id
                            WHERE i.created_at <= :asOf
                              AND n.created_at <= :asOf
                              AND (n.completed_at IS NULL OR n.completed_at > :asOf)
                              AND (n.skipped_at IS NULL OR n.skipped_at > :asOf)
                              AND n.process_name <> 'DataScope节点'
                              AND (
                                  n.branch_group IS NULL
                                  OR n.branch_group <> 'intake'
                                  OR n.branch_key = i.intake_branch_used
                              )
                              AND o.internal_status NOT IN ('COMPLETED', 'SHIPPED', 'RECEIVED')
                              AND (
                                  :dataScope = 'ALL'
                                  OR (:dataScope = 'SELF' AND n.assigned_user_id = :userId)
                              )
                        )
                        SELECT order_id, stage_name, process_name, internal_status, in_progress, overdue
                        FROM unfinished_candidates
                        WHERE row_num = 1
                        """)
                .param("asOf", asOf)
                .param("formalStandardTimeEnabled", standardTimeProperties.formalEnabled())
                .param("dataScope", dataScope)
                .param("userId", identity.userId())
                .query((rs, rowNum) -> new UnfinishedOrderMetricRow(
                        rs.getLong("order_id"),
                        rs.getString("stage_name"),
                        rs.getString("process_name"),
                        rs.getString("internal_status"),
                        rs.getInt("in_progress") == 1,
                        rs.getInt("overdue") == 1))
                .list()
                .forEach(row -> {
                    visibleOrderIds.add(row.orderId());
                    String stageName = resolveProductionKanbanStage(
                            row.stageName(), row.processName(), row.internalStatus());
                    StageMetricAccumulator metric = stages.get(stageName);
                    if (metric != null) {
                        metric.unfinished += 1;
                        if (row.inProgress()) {
                            metric.inProgress += 1;
                        }
                        if (row.overdue()) {
                            metric.overdue += 1;
                        }
                    }
                });

        jdbcClient.sql("""
                        WITH completed_candidates AS (
                            SELECT
                                i.order_id,
                                COALESCE(n.stage_name, '') AS stage_name,
                                n.process_name,
                                o.internal_status,
                                ROW_NUMBER() OVER (
                                    PARTITION BY i.order_id
                                    ORDER BY n.completed_at DESC, n.step_order DESC, n.node_instance_id DESC
                                ) AS row_num
                            FROM order_process_instance i
                            JOIN order_process_node n ON n.instance_id = i.instance_id
                            JOIN orders o ON o.order_id = i.order_id
                            WHERE i.instance_status = 'COMPLETED'
                              AND n.process_name <> 'DataScope节点'
                              AND (
                                  n.branch_group IS NULL
                                  OR n.branch_group <> 'intake'
                                  OR n.branch_key = i.intake_branch_used
                              )
                              AND i.updated_at >= :startAt
                              AND i.updated_at < :endExclusive
                              AND i.updated_at <= :asOf
                              AND n.completed_at IS NOT NULL
                              AND n.completed_at <= :asOf
                              AND (
                                  :dataScope = 'ALL'
                                  OR (:dataScope = 'SELF' AND n.assigned_user_id = :userId)
                              )
                        )
                        SELECT order_id, stage_name, process_name, internal_status
                        FROM completed_candidates
                        WHERE row_num = 1
                        """)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive)
                .param("asOf", asOf)
                .param("dataScope", dataScope)
                .param("userId", identity.userId())
                .query((rs, rowNum) -> new CompletedOrderMetricRow(
                        rs.getLong("order_id"),
                        rs.getString("stage_name"),
                        rs.getString("process_name"),
                        rs.getString("internal_status")))
                .list()
                .forEach(row -> {
                    visibleOrderIds.add(row.orderId());
                    String stageName = resolveProductionKanbanStage(
                            row.stageName(), row.processName(), row.internalStatus());
                    StageMetricAccumulator metric = stages.get(stageName);
                    if (metric != null) {
                        metric.completed += 1;
                    }
                });
    }

    private String resolveProductionKanbanStage(String rawStage, String processName, String internalStatus) {
        String stage = rawStage == null ? "" : rawStage.trim();
        if (PRODUCTION_KANBAN_STAGES.contains(stage)) {
            return stage;
        }
        String process = processName == null ? "" : processName;
        String combined = process + stage;
        if (combined.contains("外发")) return "外发加工";
        if (process.contains("质检") || List.of("COMPLETED", "PENDING_DOCTOR_CONFIRM").contains(internalStatus)) return "质检";
        if (process.contains("排牙")) return "排牙";
        if (process.contains("刻蜡") || process.contains("蜡型")) return "蜡型";
        if (process.contains("充胶")) return "充胶完成";
        if (combined.contains("钢托")) return "钢托打磨/就位";
        if (combined.contains("胶托") && containsAny(process, "打磨", "抛光", "就位", "检验")) return "胶托打磨/就位";
        if (containsAny(combined, "印模", "取模", "模型", "石膏")) return "石膏";
        if (combined.contains("车瓷")) return "车瓷";
        if (combined.contains("车金") || combined.contains("焊接")) return "车金";
        if (combined.contains("上瓷")) return "上瓷";
        if (containsAny(process, "审核", "扫描", "口扫", "下单", "收发", "取模", "检验")
                || "PROCESS_INSTANCE_CREATED".equals(internalStatus)) {
            return "CAD审核/扫描";
        }
        if (containsAny(process, "排版", "染色", "切削", "烧结", "打印")) return "CAM排版/染色/切削";
        if (containsAny(process, "打磨", "抛光", "就位")) return "胶托打磨/就位";
        if (process.contains("设计") || containsAny(stage, "CAD", "种植", "基台", "内冠", "外冠", "焊接", "贴面", "隐形", "正畸")) {
            return "CAD设计";
        }
        return "";
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private void loadQuestionKanbanMetrics(
            Map<String, StageMetricAccumulator> stages,
            LocalDateTime endExclusive,
            LocalDateTime asOf,
            BootstrapIdentity identity,
            String dataScope) {
        jdbcClient.sql("""
                        SELECT COALESCE(n.stage_name, '') AS stage_name, COUNT(DISTINCT q.order_id) AS question_count
                        FROM production_question q
                        JOIN order_process_node n ON n.node_instance_id = q.node_instance_id
                        WHERE q.asked_at < :endExclusive
                          AND (q.resolved_at IS NULL OR q.resolved_at > :asOf)
                          AND (
                              :dataScope = 'ALL'
                              OR (:dataScope = 'SELF' AND n.assigned_user_id = :userId)
                          )
                        GROUP BY COALESCE(n.stage_name, '')
                        """)
                .param("endExclusive", endExclusive)
                .param("asOf", asOf)
                .param("dataScope", dataScope)
                .param("userId", identity.userId())
                .query((rs, rowNum) -> new StageCountRow(rs.getString("stage_name"), rs.getLong("question_count")))
                .list()
                .forEach(row -> {
                    StageMetricAccumulator metric = stages.get(row.stageName());
                    if (metric != null) {
                        metric.pendingQuestions = row.count();
                    }
                });
    }

    private void loadReworkKanbanMetrics(
            Map<String, StageMetricAccumulator> stages,
            LocalDateTime endExclusive,
            LocalDateTime asOf,
            BootstrapIdentity identity,
            String dataScope) {
        jdbcClient.sql("""
                        SELECT COALESCE(target.stage_name, source.stage_name, '') AS stage_name,
                               COUNT(DISTINCT r.order_id) AS rework_count
                        FROM rework_record r
                        LEFT JOIN order_process_node target ON target.node_instance_id = r.target_node_instance_id
                        LEFT JOIN order_process_node source ON source.node_instance_id = r.from_node_instance_id
                        WHERE r.created_at < :endExclusive
                          AND (r.closed_at IS NULL OR r.closed_at > :asOf)
                          AND (
                              :dataScope = 'ALL'
                              OR (:dataScope = 'SELF' AND (
                                  target.assigned_user_id = :userId OR source.assigned_user_id = :userId
                              ))
                          )
                        GROUP BY COALESCE(target.stage_name, source.stage_name, '')
                        """)
                .param("endExclusive", endExclusive)
                .param("asOf", asOf)
                .param("dataScope", dataScope)
                .param("userId", identity.userId())
                .query((rs, rowNum) -> new StageCountRow(rs.getString("stage_name"), rs.getLong("rework_count")))
                .list()
                .forEach(row -> {
                    StageMetricAccumulator metric = stages.get(row.stageName());
                    if (metric != null) {
                        metric.internalReworks = row.count();
                    }
                });
    }

    private QuestionScope lockProductionQuestion(long questionId) {
        try {
            return jdbcClient.sql("""
                            SELECT question_id, node_instance_id
                            FROM production_question
                            WHERE question_id = :questionId
                            FOR UPDATE
                            """)
                    .param("questionId", questionId)
                    .query((rs, rowNum) -> new QuestionScope(
                            rs.getLong("question_id"), rs.getLong("node_instance_id")))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "production question not found", ex);
        }
    }

    private ProductionQuestionResponse loadProductionQuestion(long questionId) {
        return jdbcClient.sql("""
                        SELECT question_id, order_id, node_instance_id, content, status, asked_at, resolved_at
                        FROM production_question
                        WHERE question_id = :questionId
                        """)
                .param("questionId", questionId)
                .query((rs, rowNum) -> new ProductionQuestionResponse(
                        rs.getLong("question_id"),
                        rs.getLong("order_id"),
                        rs.getLong("node_instance_id"),
                        rs.getString("content"),
                        rs.getString("status"),
                        rs.getObject("asked_at", LocalDateTime.class),
                        rs.getObject("resolved_at", LocalDateTime.class)))
                .single();
    }

    private long lastInsertId() {
        return jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    private String branchParamsJson(ProductionReviewRequest request) {
        JsonNode params = request.branchParams();
        if (params == null || params.isNull()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid branch_params", ex);
        }
    }

    private void requireWorkerAssignment(NodeRow node, BootstrapIdentity identity) {
        accessControlService.requireAssignedWorkerOrAdmin(identity, node.assignedUserId(), "worker cannot operate this node");
    }

    private void requireAssignableNode(long orderId, NodeRow node) {
        if (node.orderId() != orderId) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "process node does not belong to this order");
        }
        if ("DESIGN_GATE".equals(node.nodeCategory())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "design confirmation gate is completed by the doctor confirmation flow");
        }
        if (!Set.of("PENDING", "READY", "IN_PROGRESS").contains(node.nodeStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "node cannot be assigned in current status");
        }
    }

    private void requireActiveWorker(long userId) {
        long activeWorkerCount = jdbcClient.sql("""
                        SELECT COUNT(DISTINCT user_account.user_id)
                        FROM system_user user_account
                        JOIN system_user_role user_role ON user_role.user_id = user_account.user_id
                        JOIN system_role role ON role.role_id = user_role.role_id
                        WHERE user_account.user_id = :userId
                          AND user_account.status = 'ACTIVE'
                          AND user_account.user_type = 'WORKER'
                          AND role.status = 'ACTIVE'
                          AND role.role_code = 'WORKER'
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single();
        if (activeWorkerCount == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "target user must be an active WORKER");
        }
    }

    private void recordAssignmentEvent(
            long orderId,
            NodeRow node,
            String eventType,
            long toUserId,
            String reason,
            BootstrapIdentity identity) {
        jdbcClient.sql("""
                        INSERT INTO workflow_assignment_event
                            (order_id, node_instance_id, event_type, from_user_id, to_user_id, reason, actor_user_id)
                        VALUES
                            (:orderId, :nodeInstanceId, :eventType, :fromUserId, :toUserId, :reason, :actorUserId)
                        """)
                .param("orderId", orderId)
                .param("nodeInstanceId", node.nodeInstanceId())
                .param("eventType", eventType)
                .param("fromUserId", node.assignedUserId())
                .param("toUserId", toUserId)
                .param("reason", reason)
                .param("actorUserId", identity.userId())
                .update();
    }

    private void emitAssignmentNotification(
            long orderId,
            NodeRow node,
            long recipientUserId,
            String eventType,
            String message) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "event_type", eventType,
                    "order_id", orderId,
                    "node_instance_id", node.nodeInstanceId(),
                    "process_name", node.processName(),
                    "message", message));
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "failed to build assignment notification", ex);
        }
        jdbcClient.sql("""
                        INSERT INTO notification_event
                            (order_id, event_type, audience_role, payload, delivery_status)
                        VALUES
                            (:orderId, :eventType, 'WORKER', CAST(:payload AS JSON), 'PENDING')
                        """)
                .param("orderId", orderId)
                .param("eventType", eventType)
                .param("payload", payload)
                .update();
        long eventId = lastInsertId();
        jdbcClient.sql("""
                        INSERT IGNORE INTO user_notification (event_id, user_id)
                        VALUES (:eventId, :userId)
                        """)
                .param("eventId", eventId)
                .param("userId", recipientUserId)
                .update();
        notificationPushService.pushToUser(recipientUserId, eventId, payload);
    }

    private boolean hasPassedCheck(long nodeInstanceId, String checkType) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM check_record
                        WHERE node_instance_id = :nodeInstanceId
                          AND check_type = :checkType
                          AND result = 'PASS'
                        """)
                .param("nodeInstanceId", nodeInstanceId)
                .param("checkType", checkType)
                .query(Long.class)
                .single() > 0;
    }

    private ChainRow resolveChainForOrder(long orderId, Long requestedChainId) {
        String productType = jdbcClient.sql("SELECT product_type FROM orders WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found"));
        ChainRow matched = jdbcClient.sql("""
                        SELECT chain_id, version, intake_branch
                        FROM workflow_chain
                        WHERE product_type = :productType
                          AND status = 1
                        ORDER BY version DESC, chain_id DESC
                        LIMIT 1
                        """)
                .param("productType", productType)
                .query((rs, rowNum) -> new ChainRow(
                        rs.getLong("chain_id"),
                        rs.getInt("version"),
                        rs.getString("intake_branch")))
                .optional()
                .orElse(null);
        if (matched != null) {
            if (requestedChainId != null && requestedChainId.longValue() != matched.chainId()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "requested chain does not match order product type");
            }
            return matched;
        }
        if (requestedChainId != null) {
            return loadChain(requestedChainId);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no active workflow chain matches order product type");
    }

    private void requireDoctorConfirmedDesignBeforeProductionStart(NodeRow node) {
        long startedNodeCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_process_node
                        WHERE instance_id = :instanceId
                          AND started_at IS NOT NULL
                        """)
                .param("instanceId", node.instanceId())
                .query(Long.class)
                .single();
        if (startedNodeCount > 0) {
            return;
        }
        String designTaskStatus = jdbcClient.sql("""
                        SELECT task_status
                        FROM design_task
                        WHERE order_id = :orderId
                        """)
                .param("orderId", node.orderId())
                .query(String.class)
                .optional()
                .orElse(null);
        if (designTaskStatus != null) {
            if (!"DOCTOR_CONFIRMED".equals(designTaskStatus)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "design task must be confirmed by doctor before production starts");
            }
            return;
        }
        String latestDraftStatus = jdbcClient.sql("""
                        SELECT draft_status
                        FROM design_draft
                        WHERE order_id = :orderId
                        ORDER BY version_no DESC, design_draft_id DESC
                        LIMIT 1
                        """)
                .param("orderId", node.orderId())
                .query(String.class)
                .optional()
                .orElse(null);
        if (latestDraftStatus != null && !"DOCTOR_CONFIRMED".equals(latestDraftStatus)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "latest design draft must be confirmed by doctor before production starts");
        }
    }

    private ChainRow loadChain(long chainId) {
        try {
            return jdbcClient.sql("""
                            SELECT chain_id, version, intake_branch
                            FROM workflow_chain
                            WHERE chain_id = :chainId
                              AND status = 1
                            """)
                    .param("chainId", chainId)
                    .query((rs, rowNum) -> new ChainRow(
                            rs.getLong("chain_id"),
                            rs.getInt("version"),
                            rs.getString("intake_branch")))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "workflow chain not found", ex);
        }
    }

    private InstanceRow loadInstanceByOrder(long orderId) {
        try {
            return jdbcClient.sql("""
                            SELECT instance_id, order_id, instance_status, intake_branch_used, created_at, updated_at
                            FROM order_process_instance
                            WHERE order_id = :orderId
                            """)
                    .param("orderId", orderId)
                    .query((rs, rowNum) -> new InstanceRow(
                            rs.getLong("instance_id"),
                            rs.getLong("order_id"),
                            rs.getString("instance_status"),
                            rs.getString("intake_branch_used"),
                            rs.getObject("created_at", LocalDateTime.class),
                            rs.getObject("updated_at", LocalDateTime.class)))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "process instance not found", ex);
        }
    }

    private InstanceRow loadInstanceByOrder(long orderId, BootstrapIdentity identity) {
        String dataScope = accessControlService.effectiveDataScope(identity);
        accessControlService.requireScopedIdentity(identity, dataScope);
        try {
            return jdbcClient.sql("""
                            SELECT i.instance_id, i.order_id, i.instance_status, i.intake_branch_used,
                                   i.created_at, i.updated_at
                            FROM order_process_instance i
                            JOIN orders o ON o.order_id = i.order_id
                            WHERE i.order_id = :orderId
                              AND (
                                  :dataScope = 'ALL'
                                  OR (:dataScope = 'CLINIC'
                                      AND (o.clinic_id = :clinicId OR o.doctor_user_id = :userId))
                                  OR (:dataScope = 'SELF'
                                      AND (
                                          EXISTS (
                                              SELECT 1
                                              FROM order_process_node scoped_n
                                              WHERE scoped_n.instance_id = i.instance_id
                                                AND scoped_n.assigned_user_id = :userId
                                          )
                                          OR EXISTS (
                                              SELECT 1
                                              FROM design_task scoped_design
                                              WHERE scoped_design.order_id = i.order_id
                                                AND scoped_design.assigned_user_id = :userId
                                                AND scoped_design.task_status <> 'CANCELLED'
                                          )
                                      ))
                              )
                            """)
                    .param("orderId", orderId)
                    .param("dataScope", dataScope)
                    .param("userId", identity.userId())
                    .param("clinicId", identity.clinicId())
                    .query((rs, rowNum) -> new InstanceRow(
                            rs.getLong("instance_id"),
                            rs.getLong("order_id"),
                            rs.getString("instance_status"),
                            rs.getString("intake_branch_used"),
                            rs.getObject("created_at", LocalDateTime.class),
                            rs.getObject("updated_at", LocalDateTime.class)))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            if (processInstanceExists(orderId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "identity cannot access process instance", ex);
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "process instance not found", ex);
        }
    }

    private void ensureInstanceForOrder(long orderId) {
        loadInstanceByOrder(orderId);
    }

    private boolean processInstanceExists(long orderId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_process_instance
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single() > 0;
    }

    private NodeRow lockNode(long nodeInstanceId) {
        try {
            return jdbcClient.sql("""
                            SELECT
                                n.node_instance_id,
                                n.instance_id,
                                i.order_id,
                                n.process_name,
                                n.node_category,
                                n.is_optional,
                                n.need_in_check,
                                n.need_out_check,
                                n.assigned_user_id,
                                n.node_status
                            FROM order_process_node n
                            JOIN order_process_instance i ON i.instance_id = n.instance_id
                            WHERE n.node_instance_id = :nodeInstanceId
                            FOR UPDATE
                            """)
                    .param("nodeInstanceId", nodeInstanceId)
                    .query((rs, rowNum) -> new NodeRow(
                            rs.getLong("node_instance_id"),
                            rs.getLong("instance_id"),
                            rs.getLong("order_id"),
                            rs.getString("process_name"),
                            rs.getString("node_category"),
                            rs.getInt("is_optional"),
                            rs.getInt("need_in_check"),
                            rs.getInt("need_out_check"),
                            rs.getObject("assigned_user_id", Long.class),
                            rs.getString("node_status")))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "process node not found", ex);
        }
    }

    private List<ProcessNodeResponse> loadNodes(long instanceId) {
        return jdbcClient.sql("""
                        SELECT
                            node_instance_id,
                            node_code,
                            process_name,
                            stage_name,
                            node_category,
                            step_order,
                            is_optional,
                            branch_group,
                            branch_key,
                            assigned_user_id,
                            node_status,
                            CASE
                                WHEN :formalStandardTimeEnabled THEN standard_duration
                                ELSE NULL
                            END AS standard_duration,
                            started_at,
                            CASE
                                WHEN :formalStandardTimeEnabled THEN deadline_at
                                ELSE NULL
                            END AS deadline_at,
                            completed_at,
                            CASE
                                WHEN node_status = 'READY'
                                     AND (need_in_check = 0 OR EXISTS (
                                         SELECT 1
                                         FROM check_record in_check
                                         WHERE in_check.node_instance_id = order_process_node.node_instance_id
                                           AND in_check.check_type = 'IN'
                                           AND in_check.result = 'PASS'
                                     ))
                                THEN TRUE
                                ELSE FALSE
                            END AS can_start,
                            CASE
                                WHEN node_status = 'READY'
                                     AND need_in_check = 1
                                     AND NOT EXISTS (
                                         SELECT 1
                                         FROM check_record in_check
                                         WHERE in_check.node_instance_id = order_process_node.node_instance_id
                                           AND in_check.check_type = 'IN'
                                           AND in_check.result = 'PASS'
                                     )
                                THEN 'IN_CHECK_REQUIRED'
                                ELSE NULL
                            END AS start_block_reason
                        FROM order_process_node
                        JOIN order_process_instance instance
                          ON instance.instance_id = order_process_node.instance_id
                        WHERE order_process_node.instance_id = :instanceId
                          AND (
                              order_process_node.branch_group IS NULL
                              OR order_process_node.branch_group <> 'intake'
                              OR order_process_node.branch_key = instance.intake_branch_used
                          )
                        ORDER BY step_order, node_instance_id
                        """)
                .param("instanceId", instanceId)
                .param("formalStandardTimeEnabled", standardTimeProperties.formalEnabled())
                .query((rs, rowNum) -> new ProcessNodeResponse(
                        rs.getLong("node_instance_id"),
                        rs.getString("node_code"),
                        rs.getString("process_name"),
                        rs.getString("stage_name"),
                        rs.getString("node_category"),
                        rs.getInt("step_order"),
                        rs.getInt("is_optional"),
                        rs.getString("branch_group"),
                        rs.getString("branch_key"),
                        rs.getObject("assigned_user_id", Long.class),
                        rs.getString("node_status"),
                        rs.getObject("standard_duration", Integer.class),
                        rs.getObject("started_at", java.time.LocalDateTime.class),
                        rs.getObject("deadline_at", java.time.LocalDateTime.class),
                        rs.getObject("completed_at", java.time.LocalDateTime.class),
                        rs.getBoolean("can_start"),
                        rs.getString("start_block_reason")))
                .list();
    }

    private List<ProcessEdgeResponse> loadEdges(long instanceId) {
        return jdbcClient.sql("""
                        SELECT
                            edge_instance_id,
                            from_node_instance_id,
                            to_node_instance_id,
                            edge_type
                        FROM order_process_edge
                        WHERE instance_id = :instanceId
                        ORDER BY edge_instance_id
                        """)
                .param("instanceId", instanceId)
                .query((rs, rowNum) -> new ProcessEdgeResponse(
                        rs.getLong("edge_instance_id"),
                        rs.getLong("from_node_instance_id"),
                        rs.getLong("to_node_instance_id"),
                        rs.getString("edge_type")))
                .list();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record ChainRow(long chainId, int version, String intakeBranch) {
    }

    private record InstanceRow(
            long instanceId,
            long orderId,
            String instanceStatus,
            String intakeBranchUsed,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    private record NodeRow(
            long nodeInstanceId,
            long instanceId,
            long orderId,
            String processName,
            String nodeCategory,
            int isOptional,
            int needInCheck,
            int needOutCheck,
            Long assignedUserId,
            String nodeStatus) {
    }

    private record UnfinishedOrderMetricRow(
            long orderId,
            String stageName,
            String processName,
            String internalStatus,
            boolean inProgress,
            boolean overdue) {
    }

    private record CompletedOrderMetricRow(
            long orderId, String stageName, String processName, String internalStatus) {
    }

    private record StageCountRow(String stageName, long count) {
    }

    private record QuestionScope(long questionId, long nodeInstanceId) {
    }

    private static final class StageMetricAccumulator {
        private long unfinished;
        private long inProgress;
        private long completed;
        private long overdue;
        private long pendingQuestions;
        private long internalReworks;
    }

    private record DefinitionNode(
            long nodeId,
            String nodeCode,
            String processName,
            String stageName,
            int stepOrder,
            int isOptional,
            String branchGroup,
            String branchKey,
            Integer standardDuration,
            String defaultRole,
            String nodeCategory,
            int needInCheck,
            int needOutCheck) {
    }

    private record InstanceNode(long sourceNodeId, long nodeInstanceId) {
    }

    private record DefinitionEdge(
            long fromNodeId,
            long toNodeId,
            String edgeType,
            String conditionKey) {
    }

    private record ProjectedEdge(
            long fromNodeInstanceId,
            long toNodeInstanceId,
            String edgeType,
            String conditionKey) {
    }
}
