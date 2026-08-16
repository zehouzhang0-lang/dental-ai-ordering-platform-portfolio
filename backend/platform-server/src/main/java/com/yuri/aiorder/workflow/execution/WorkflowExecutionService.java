package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.BusinessTime;
import com.yuri.aiorder.common.auth.AccessControlService;
import com.yuri.aiorder.common.auth.SystemConfigService;
import com.yuri.aiorder.workflow.runtime.WorkflowRuntimeService;
import com.yuri.aiorder.workflow.standardtime.WorkflowStandardTimeProperties;
import com.yuri.aiorder.notification.NotificationPushService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkflowExecutionService {

    private static final String PERFORMANCE_FORMULA_VERSION = "PHASE_ONE_DEFAULT_V1";
    private static final ZoneId WORKBENCH_ZONE = BusinessTime.ZONE;
    private static final List<WorkbenchDepartmentDefinition> WORKBENCH_DEPARTMENTS = List.of(
            new WorkbenchDepartmentDefinition("DATA_REVIEW", "数据处理", "Data Review", 10),
            new WorkbenchDepartmentDefinition("CAD", "CAD", "Design", 20),
            new WorkbenchDepartmentDefinition("IMPLANT", "种植", "Implant", 30),
            new WorkbenchDepartmentDefinition("MILLING", "车金切削", "Milling", 40),
            new WorkbenchDepartmentDefinition("PRINTING_3D", "3D打印", "3D Printing", 50),
            new WorkbenchDepartmentDefinition("PORCELAIN", "车瓷", "Porcelain", 60),
            new WorkbenchDepartmentDefinition("STAINING", "上瓷上釉", "Staining", 70),
            new WorkbenchDepartmentDefinition("STEEL_FRAMEWORK", "钢托", "Steel Framework", 80),
            new WorkbenchDepartmentDefinition("ACRYLIC", "胶托", "Acrylic", 90),
            new WorkbenchDepartmentDefinition("FLEXIBLE", "隐形", "Flexible", 100),
            new WorkbenchDepartmentDefinition("ORTHO", "正畸", "Ortho", 110),
            new WorkbenchDepartmentDefinition("QC", "质检", "QC", 120),
            new WorkbenchDepartmentDefinition("DISPATCH", "包装出货", "Dispatch", 130));

    private static final TypeReference<List<Long>> LONG_LIST_TYPE = new TypeReference<>() {
    };

    private static final String REWORK_REASON_CATEGORY_TYPE = "REASON_CATEGORY";
    private static final String REWORK_RESPONSIBILITY_TYPE = "RESPONSIBILITY_TYPE";
    private static final Set<String> REWORK_DICTIONARY_TYPES = Set.of(
            REWORK_REASON_CATEGORY_TYPE,
            REWORK_RESPONSIBILITY_TYPE);
    private static final Set<String> REWORK_DICTIONARY_STATUS = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> EQUIPMENT_STATUSES = Set.of(
            "RUNNING", "IDLE", "MAINTENANCE", "FAULT", "SCRAPPED");
    private static final Set<String> EQUIPMENT_EVENT_TYPES = Set.of(
            "MAINTENANCE_PLAN", "CALIBRATION", "FAULT_REPAIR", "DOWNTIME", "REPAIR_REQUEST", "SCRAP_REQUEST");
    private static final Set<String> EQUIPMENT_EVENT_STATUSES = Set.of(
            "PENDING", "IN_PROGRESS", "DONE", "APPROVED", "REJECTED");
    private static final Set<String> EQUIPMENT_APPROVAL_TYPES = Set.of("REPAIR_REQUEST", "SCRAP_REQUEST");
    private static final Set<String> MATERIAL_EXCEPTION_TYPES = Set.of(
            "SHORTAGE", "WRONG_MATERIAL", "BATCH_ABNORMAL", "MATERIAL_LOSS");
    private static final Set<String> MATERIAL_EXCEPTION_STATUSES = Set.of("PENDING", "IN_PROGRESS", "CLOSED");
    private static final Set<String> SAFETY_ENVIRONMENT_EVENT_TYPES = Set.of(
            "SAFETY_INSPECTION", "HAZARD_RECTIFICATION", "ENVIRONMENT_RECORD", "PPE_DEVICE_REMINDER");
    private static final Set<String> SAFETY_ENVIRONMENT_EVENT_STATUSES = Set.of("PENDING", "IN_PROGRESS", "CLOSED");
    private static final Set<String> SAFETY_ENVIRONMENT_RISK_LEVELS = Set.of("NORMAL", "HIGH", "CRITICAL");
    private static final Set<String> PRODUCTION_COST_TYPES = Set.of(
            "PROCESS", "MATERIAL", "LABOR", "REWORK", "OUTSOURCING");
    private static final Set<String> PRODUCTION_COST_STATUSES = Set.of("NORMAL", "WARNING", "CONFIRMED");
    private static final Set<String> PRODUCTION_REWARD_PENALTY_TYPES = Set.of("REWARD", "PENALTY");
    private static final Set<String> PRODUCTION_REWARD_PENALTY_STATUSES = Set.of(
            "PENDING", "APPROVED", "REJECTED", "EFFECTIVE");
    private static final Set<String> PRODUCTION_REWARD_PENALTY_REASON_CATEGORIES = Set.of(
            "QUALITY", "EFFICIENCY", "DISCIPLINE", "SAFETY", "CUSTOMER_FEEDBACK");

    private final JdbcClient jdbcClient;
    private final AccessControlService accessControlService;
    private final ObjectMapper objectMapper;
    private final NotificationPushService notificationPushService;
    private final WorkflowRuntimeService workflowRuntimeService;
    private final WorkflowStandardTimeProperties standardTimeProperties;
    private final SystemConfigService systemConfigService;

    public WorkflowExecutionService(
            JdbcClient jdbcClient,
            AccessControlService accessControlService,
            ObjectMapper objectMapper,
            NotificationPushService notificationPushService,
            WorkflowRuntimeService workflowRuntimeService,
            WorkflowStandardTimeProperties standardTimeProperties,
            SystemConfigService systemConfigService) {
        this.jdbcClient = jdbcClient;
        this.accessControlService = accessControlService;
        this.objectMapper = objectMapper;
        this.notificationPushService = notificationPushService;
        this.workflowRuntimeService = workflowRuntimeService;
        this.standardTimeProperties = standardTimeProperties;
        this.systemConfigService = systemConfigService;
    }

    @Transactional
    public CheckRecordResponse submitCheck(CheckRecordRequest request, BootstrapIdentity identity) {
        if (request.nodeInstanceId() == null || request.checkType() == null || request.isPass() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "node_instance_id, check_type and is_pass are required");
        }
        NodeRow node = lockNode(request.nodeInstanceId());
        requireWorkerAssignment(node, identity);
        String checkType = normalizeCheckType(request.checkType());
        // 客户规则：入检 / 出检的检查人是组长，质检员只做过程抽检。
        if ("SAMPLE".equals(checkType)) {
            accessControlService.requireSampleInspection(identity);
        } else {
            accessControlService.requireGateInspection(identity);
        }
        if ("IN".equals(checkType) && !"READY".equals(node.nodeStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "in-check requires ready node");
        }
        if ("OUT".equals(checkType) && !"COMPLETED".equals(node.nodeStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "out-check requires completed node");
        }
        String result = Boolean.TRUE.equals(request.isPass()) ? "PASS" : "FAIL";
        jdbcClient.sql("""
                        INSERT INTO check_record
                            (order_id, node_instance_id, check_type, result, checker_user_id, note)
                        VALUES
                            (:orderId, :nodeInstanceId, :checkType, :result, :checkerUserId, :note)
                        """)
                .param("orderId", node.orderId())
                .param("nodeInstanceId", node.nodeInstanceId())
                .param("checkType", checkType)
                .param("result", result)
                .param("checkerUserId", identity.userId())
                .param("note", request.remark())
                .update();
        long checkId = lastInsertId();
        Long reworkId = null;
        if ("OUT".equals(checkType) && "FAIL".equals(result)) {
            reworkId = createRework(node, checkId, request);
        }
        if ("OUT".equals(checkType) && "PASS".equals(result)) {
            workflowRuntimeService.activateAfterPassedOutCheck(node.nodeInstanceId());
        }
        return new CheckRecordResponse(checkId, node.nodeInstanceId(), request.checkType(), result, reworkId);
    }

    public List<CheckRecordResponse> getChecks(long nodeInstanceId, BootstrapIdentity identity) {
        accessControlService.requireCheckRecordRead(identity);
        return jdbcClient.sql("""
                        SELECT check_id, node_instance_id, check_type, result
                        FROM check_record
                        WHERE node_instance_id = :nodeInstanceId
                        ORDER BY check_id
                        """)
                .param("nodeInstanceId", nodeInstanceId)
                .query((rs, rowNum) -> new CheckRecordResponse(
                        rs.getLong("check_id"),
                        rs.getLong("node_instance_id"),
                        denormalizeCheckType(rs.getString("check_type")),
                        rs.getString("result"),
                        null))
                .list();
    }

    public List<ReworkRecordResponse> getReworks(
            String status, Long orderId, Boolean hasImpactedNodes, BootstrapIdentity identity) {
        accessControlService.requireCheckRecordRead(identity);
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        String statusClause = normalizedStatus == null ? "" : " AND r.status = :status";
        String orderClause = orderId == null ? "" : " AND r.order_id = :orderId";
        String impactedClause = hasImpactedNodes == null
                ? ""
                : Boolean.TRUE.equals(hasImpactedNodes)
                        ? " AND r.impacted_node_count > 0"
                        : " AND r.impacted_node_count = 0";
        String workerClause = identity.role() == com.yuri.aiorder.common.UserRole.WORKER
                ? " AND (target_node.assigned_user_id = :workerUserId OR from_node.assigned_user_id = :workerUserId)"
                : "";
        JdbcClient.StatementSpec spec = jdbcClient.sql("""
                        SELECT
                            r.rework_id,
                            r.order_id,
                            o.order_no,
                            r.source_check_id,
                            r.from_node_instance_id,
                            from_node.process_name AS from_process_name,
                            r.target_node_instance_id,
                            target_node.process_name AS target_process_name,
                            target_node.node_status AS target_node_status,
                            r.impacted_node_count,
                            CAST(r.impacted_node_instance_ids AS CHAR) AS impacted_node_instance_ids,
                            target_node.assigned_user_id,
                            r.reason_category,
                            r.reason_detail,
                            r.responsibility_type,
                            r.routed_dept_id,
                            r.routed_to_user_id,
                            r.close_note,
                            r.closed_by_user_id,
                            r.closed_at,
                            r.status,
                            r.created_at
                        FROM rework_record r
                        JOIN orders o ON o.order_id = r.order_id
                        LEFT JOIN order_process_node from_node
                          ON from_node.node_instance_id = r.from_node_instance_id
                        LEFT JOIN order_process_node target_node
                          ON target_node.node_instance_id = r.target_node_instance_id
                        WHERE 1 = 1
                        %s
                        %s
                        %s
                        %s
                        ORDER BY r.created_at DESC, r.rework_id DESC
                        LIMIT 100
                        """.formatted(statusClause, orderClause, impactedClause, workerClause));
        if (normalizedStatus != null) {
            spec = spec.param("status", normalizedStatus);
        }
        if (orderId != null) {
            spec = spec.param("orderId", orderId);
        }
        if (identity.role() == com.yuri.aiorder.common.UserRole.WORKER) {
            if (identity.userId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "worker user id is required");
            }
            spec = spec.param("workerUserId", identity.userId());
        }
        return spec.query((rs, rowNum) -> new ReworkRecordResponse(
                        rs.getLong("rework_id"),
                        rs.getLong("order_id"),
                        rs.getString("order_no"),
                        rs.getLong("source_check_id"),
                        rs.getObject("from_node_instance_id", Long.class),
                        rs.getString("from_process_name"),
                        rs.getObject("target_node_instance_id", Long.class),
                        rs.getString("target_process_name"),
                        rs.getString("target_node_status"),
                        rs.getInt("impacted_node_count"),
                        parseImpactedNodeInstanceIds(rs.getString("impacted_node_instance_ids")),
                        rs.getObject("assigned_user_id", Long.class),
                        rs.getString("reason_category"),
                        rs.getString("reason_detail"),
                        rs.getString("responsibility_type"),
                        rs.getObject("routed_dept_id", Long.class),
                        rs.getObject("routed_to_user_id", Long.class),
                        rs.getString("close_note"),
                        rs.getObject("closed_by_user_id", Long.class),
                        rs.getObject("closed_at", LocalDateTime.class),
                        rs.getString("status"),
                        rs.getObject("created_at", LocalDateTime.class)))
                .list();
    }

    public ReworkDictionariesResponse getReworkDictionaries(BootstrapIdentity identity) {
        accessControlService.requireCheckRecordRead(identity);
        return new ReworkDictionariesResponse(
                listActiveReworkDictionaryOptions(REWORK_REASON_CATEGORY_TYPE),
                listActiveReworkDictionaryOptions(REWORK_RESPONSIBILITY_TYPE));
    }

    public List<ReworkDictionaryItemResponse> listReworkDictionaryItems(String dictionaryType) {
        String normalizedType = dictionaryType == null || dictionaryType.isBlank()
                ? null
                : normalizeReworkDictionaryType(dictionaryType);
        String typeClause = normalizedType == null ? "" : " WHERE dictionary_type = :dictionaryType";
        JdbcClient.StatementSpec spec = jdbcClient.sql("""
                        SELECT item_id, dictionary_type, item_code, item_label, sort_order, status
                        FROM rework_dictionary_item
                        %s
                        ORDER BY dictionary_type, sort_order, item_id
                        """.formatted(typeClause));
        if (normalizedType != null) {
            spec = spec.param("dictionaryType", normalizedType);
        }
        return spec.query((rs, rowNum) -> new ReworkDictionaryItemResponse(
                        rs.getLong("item_id"),
                        rs.getString("dictionary_type"),
                        rs.getString("item_code"),
                        rs.getString("item_label"),
                        rs.getInt("sort_order"),
                        rs.getString("status")))
                .list();
    }

    @Transactional
    public ReworkDictionaryItemResponse createReworkDictionaryItem(CreateReworkDictionaryItemRequest request) {
        String dictionaryType = normalizeReworkDictionaryType(request.dictionaryType());
        String code = normalizeRequired(request.code(), "code").toUpperCase(Locale.ROOT);
        String label = normalizeRequired(request.label(), "label");
        int sortOrder = request.sortOrder() == null ? 0 : request.sortOrder();
        try {
            jdbcClient.sql("""
                            INSERT INTO rework_dictionary_item
                                (dictionary_type, item_code, item_label, sort_order, status)
                            VALUES
                                (:dictionaryType, :code, :label, :sortOrder, 'ACTIVE')
                            """)
                    .param("dictionaryType", dictionaryType)
                    .param("code", code)
                    .param("label", label)
                    .param("sortOrder", sortOrder)
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "rework dictionary item already exists", ex);
        }
        return requireReworkDictionaryItem(lastInsertId());
    }

    @Transactional
    public ReworkDictionaryItemResponse updateReworkDictionaryItem(
            long itemId, UpdateReworkDictionaryItemRequest request) {
        requireReworkDictionaryItem(itemId);
        String label = request.label() == null ? null : normalizeRequired(request.label(), "label");
        String status = request.status() == null ? null : normalizeReworkDictionaryStatus(request.status());
        jdbcClient.sql("""
                        UPDATE rework_dictionary_item
                        SET item_label = COALESCE(:label, item_label),
                            sort_order = COALESCE(:sortOrder, sort_order),
                            status = COALESCE(:status, status)
                        WHERE item_id = :itemId
                        """)
                .param("label", label)
                .param("sortOrder", request.sortOrder())
                .param("status", status)
                .param("itemId", itemId)
                .update();
        return requireReworkDictionaryItem(itemId);
    }

    public ProductionQualitySummaryResponse getProductionQualitySummary(
            String productType, LocalDate startDate, LocalDate endDate, BootstrapIdentity identity) {
        accessControlService.requireCheckRecordRead(identity);
        String normalizedProductType = blankToNull(productType);
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "end_date must not be before start_date");
        }
        String productTypeClause = normalizedProductType == null ? "" : " AND o.product_type = :productType";
        String checkDateClause = startDate == null ? "" : " AND c.created_at >= :startAt";
        checkDateClause += endDate == null ? "" : " AND c.created_at < :endExclusive";
        String reworkDateClause = startDate == null ? "" : " AND r.created_at >= :startAt";
        reworkDateClause += endDate == null ? "" : " AND r.created_at < :endExclusive";

        JdbcClient.StatementSpec checkSpec = jdbcClient.sql("""
                        WITH ranked_out_checks AS (
                            SELECT
                                c.order_id,
                                c.result,
                                ROW_NUMBER() OVER (
                                    PARTITION BY c.order_id
                                    ORDER BY c.created_at ASC, c.check_id ASC
                                ) AS first_rank,
                                ROW_NUMBER() OVER (
                                    PARTITION BY c.order_id
                                    ORDER BY c.created_at DESC, c.check_id DESC
                                ) AS latest_rank
                            FROM check_record c
                            JOIN orders o ON o.order_id = c.order_id
                            WHERE c.check_type = 'OUT'
                        """ + productTypeClause + checkDateClause + """
                        )
                        SELECT
                            COUNT(DISTINCT order_id) AS inspected_order_count,
                            COALESCE(SUM(CASE WHEN first_rank = 1 AND result = 'PASS' THEN 1 ELSE 0 END), 0)
                                AS first_pass_count,
                            COALESCE(SUM(CASE WHEN latest_rank = 1 AND result = 'PASS' THEN 1 ELSE 0 END), 0)
                                AS final_pass_count
                        FROM ranked_out_checks
                        """);
        if (normalizedProductType != null) {
            checkSpec = checkSpec.param("productType", normalizedProductType);
        }
        checkSpec = bindQualityDateRange(checkSpec, startDate, endDate);
        QualityCheckSummaryRow checkSummary = checkSpec.query((rs, rowNum) -> new QualityCheckSummaryRow(
                        rs.getLong("inspected_order_count"),
                        rs.getLong("first_pass_count"),
                        rs.getLong("final_pass_count")))
                .single();

        JdbcClient.StatementSpec reworkSpec = jdbcClient.sql("""
                        SELECT
                            COUNT(*) AS total_rework_count,
                            COALESCE(SUM(CASE WHEN r.responsibility_type = 'WORKER' THEN 1 ELSE 0 END), 0)
                                AS internal_rework_count,
                            COALESCE(SUM(CASE WHEN r.responsibility_type IN ('DOCTOR', 'CS') THEN 1 ELSE 0 END), 0)
                                AS external_rework_count,
                            COALESCE(SUM(CASE
                                WHEN r.responsibility_type IS NULL
                                     OR r.responsibility_type NOT IN ('WORKER', 'DOCTOR', 'CS')
                                THEN 1 ELSE 0 END), 0) AS unclassified_rework_count
                        FROM rework_record r
                        JOIN orders o ON o.order_id = r.order_id
                        WHERE 1 = 1
                        """ + productTypeClause + reworkDateClause);
        if (normalizedProductType != null) {
            reworkSpec = reworkSpec.param("productType", normalizedProductType);
        }
        reworkSpec = bindQualityDateRange(reworkSpec, startDate, endDate);
        QualityReworkSummaryRow reworkSummary = reworkSpec.query((rs, rowNum) -> new QualityReworkSummaryRow(
                        rs.getLong("total_rework_count"),
                        rs.getLong("internal_rework_count"),
                        rs.getLong("external_rework_count"),
                        rs.getLong("unclassified_rework_count")))
                .single();

        String complaintDateClause = startDate == null ? "" : " AND qr.created_at >= :startAt";
        complaintDateClause += endDate == null ? "" : " AND qr.created_at < :endExclusive";
        JdbcClient.StatementSpec complaintSpec = jdbcClient.sql("""
                        SELECT COUNT(*) AS complaint_count
                        FROM quality_record qr
                        JOIN orders o ON o.order_id = qr.order_id
                        WHERE qr.record_type = 'EXTERNAL_RETURN'
                        """ + productTypeClause + complaintDateClause);
        if (normalizedProductType != null) {
            complaintSpec = complaintSpec.param("productType", normalizedProductType);
        }
        complaintSpec = bindQualityDateRange(complaintSpec, startDate, endDate);
        long complaintCount = complaintSpec.query(Long.class).single();

        long inspectedOrderCount = checkSummary.inspectedOrderCount();
        List<ProductionQualitySummaryResponse.TrendPoint> trends = loadProductionQualityTrends(
                normalizedProductType, startDate, endDate);
        return new ProductionQualitySummaryResponse(
                normalizedProductType,
                inspectedOrderCount,
                reworkSummary.totalReworkCount(),
                reworkSummary.internalReworkCount(),
                reworkSummary.externalReworkCount(),
                reworkSummary.unclassifiedReworkCount(),
                percentage(reworkSummary.totalReworkCount(), inspectedOrderCount),
                percentage(reworkSummary.internalReworkCount(), inspectedOrderCount),
                percentage(reworkSummary.externalReworkCount(), inspectedOrderCount),
                percentage(checkSummary.firstPassCount(), inspectedOrderCount),
                percentage(checkSummary.finalPassCount(), inspectedOrderCount),
                complaintCount,
                percentage(complaintCount, inspectedOrderCount),
                null,
                startDate,
                endDate,
                trends,
                LocalDateTime.now());
    }

    private JdbcClient.StatementSpec bindQualityDateRange(
            JdbcClient.StatementSpec spec, LocalDate startDate, LocalDate endDate) {
        if (startDate != null) {
            spec = spec.param("startAt", startDate.atStartOfDay());
        }
        if (endDate != null) {
            spec = spec.param("endExclusive", endDate.plusDays(1).atStartOfDay());
        }
        return spec;
    }

    private List<ProductionQualitySummaryResponse.TrendPoint> loadProductionQualityTrends(
            String productType, LocalDate startDate, LocalDate endDate) {
        String productTypeClause = productType == null ? "" : " AND o.product_type = :productType";
        String dateClause = startDate == null ? "" : " AND c.created_at >= :startAt";
        dateClause += endDate == null ? "" : " AND c.created_at < :endExclusive";
        JdbcClient.StatementSpec spec = jdbcClient.sql("""
                        WITH daily_checks AS (
                            SELECT DATE(c.created_at) AS trend_date,
                                   c.order_id,
                                   c.result,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY DATE(c.created_at), c.order_id
                                       ORDER BY c.created_at ASC, c.check_id ASC
                                   ) AS first_rank,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY DATE(c.created_at), c.order_id
                                       ORDER BY c.created_at DESC, c.check_id DESC
                                   ) AS latest_rank
                            FROM check_record c
                            JOIN orders o ON o.order_id = c.order_id
                            WHERE c.check_type = 'OUT'
                        """ + productTypeClause + dateClause + """
                        ), daily_reworks AS (
                            SELECT DATE(r.created_at) AS trend_date, COUNT(*) AS rework_count
                            FROM rework_record r
                            JOIN orders o ON o.order_id = r.order_id
                            WHERE 1 = 1
                        """ + productTypeClause.replace("c.", "r.")
                + (startDate == null ? "" : " AND r.created_at >= :startAt")
                + (endDate == null ? "" : " AND r.created_at < :endExclusive") + """
                            GROUP BY DATE(r.created_at)
                        )
                        SELECT dc.trend_date,
                               COUNT(DISTINCT dc.order_id) AS inspected_order_count,
                               COALESCE(MAX(dr.rework_count), 0) AS rework_count,
                               COALESCE(SUM(CASE WHEN dc.first_rank = 1 AND dc.result = 'PASS' THEN 1 ELSE 0 END), 0)
                                   AS first_pass_count,
                               COALESCE(SUM(CASE WHEN dc.latest_rank = 1 AND dc.result = 'PASS' THEN 1 ELSE 0 END), 0)
                                   AS final_pass_count
                        FROM daily_checks dc
                        LEFT JOIN daily_reworks dr ON dr.trend_date = dc.trend_date
                        GROUP BY dc.trend_date
                        ORDER BY dc.trend_date
                        """);
        if (productType != null) {
            spec = spec.param("productType", productType);
        }
        spec = bindQualityDateRange(spec, startDate, endDate);
        return spec.query((rs, rowNum) -> {
                    long inspected = rs.getLong("inspected_order_count");
                    return new ProductionQualitySummaryResponse.TrendPoint(
                            rs.getObject("trend_date", LocalDate.class),
                            inspected,
                            rs.getLong("rework_count"),
                            percentage(rs.getLong("first_pass_count"), inspected),
                            percentage(rs.getLong("final_pass_count"), inspected));
                })
                .list();
    }

    public ProductionWorkbenchDepartmentSummaryResponse getProductionWorkbenchDepartmentSummary(
            String orderNoPrefix, BootstrapIdentity identity) {
        accessControlService.requireCheckRecordRead(identity);
        String normalizedOrderNoPrefix = blankToNull(orderNoPrefix);
        LocalDate today = LocalDate.now(WORKBENCH_ZONE);
        LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();
        YearMonth lastMonth = YearMonth.from(today).minusMonths(1);
        LocalDateTime lastMonthStart = lastMonth.atDay(1).atStartOfDay();
        YearMonth currentMonth = YearMonth.from(today);

        Map<String, WorkbenchDepartmentAccumulator> departments = newWorkbenchDepartmentAccumulators();
        loadWorkbenchCompletedWorkLogs(normalizedOrderNoPrefix, lastMonthStart, tomorrowStart, departments);
        loadWorkbenchCompletedNodes(normalizedOrderNoPrefix, lastMonthStart, tomorrowStart, departments);
        loadWorkbenchReworks(normalizedOrderNoPrefix, lastMonthStart, tomorrowStart, departments);
        loadWorkbenchActiveNodes(normalizedOrderNoPrefix, departments);

        List<ProductionWorkbenchDepartmentSummaryResponse.DepartmentRow> rows = WORKBENCH_DEPARTMENTS.stream()
                .sorted(Comparator.comparingInt(WorkbenchDepartmentDefinition::displayOrder))
                .map(department -> buildWorkbenchDepartmentRow(
                        department,
                        departments.get(department.key()),
                        today,
                        lastMonth,
                        currentMonth))
                .toList();
        List<LocalDate> trendDates = new ArrayList<>();
        for (int index = 6; index >= 0; index--) {
            trendDates.add(today.minusDays(index));
        }
        List<ProductionWorkbenchDepartmentSummaryResponse.DepartmentTrend> trends = new ArrayList<>();
        trends.add(buildAllWorkbenchTrend(departments, trendDates, today));
        WORKBENCH_DEPARTMENTS.stream()
                .sorted(Comparator.comparingInt(WorkbenchDepartmentDefinition::displayOrder))
                .map(department -> buildWorkbenchTrend(department, departments.get(department.key()), trendDates, today))
                .forEach(trends::add);

        return new ProductionWorkbenchDepartmentSummaryResponse(
                LocalDateTime.now(WORKBENCH_ZONE),
                today,
                lastMonth.toString(),
                rows,
                List.of(
                        new ProductionWorkbenchDepartmentSummaryResponse.TrendMetric("completion_rate", "完成率"),
                        new ProductionWorkbenchDepartmentSummaryResponse.TrendMetric("rework_rate", "返工率"),
                        new ProductionWorkbenchDepartmentSummaryResponse.TrendMetric("shipping_rate", "出货率")),
                trends);
    }

    private Map<String, WorkbenchDepartmentAccumulator> newWorkbenchDepartmentAccumulators() {
        Map<String, WorkbenchDepartmentAccumulator> departments = new LinkedHashMap<>();
        WORKBENCH_DEPARTMENTS.forEach(department ->
                departments.put(department.key(), new WorkbenchDepartmentAccumulator()));
        return departments;
    }

    private void loadWorkbenchCompletedWorkLogs(
            String orderNoPrefix,
            LocalDateTime startAt,
            LocalDateTime endExclusive,
            Map<String, WorkbenchDepartmentAccumulator> departments) {
        String orderClause = orderNoPrefix == null ? "" : " AND o.order_no LIKE :orderNoPattern\n";
        JdbcClient.StatementSpec spec = jdbcClient.sql("""
                        SELECT
                            COALESCE(n.stage_name, '') AS stage_name,
                            COALESCE(n.process_name, '') AS process_name,
                            COALESCE(n.node_category, '') AS node_category,
                            DATE(w.finished_at) AS production_date,
                            COUNT(*) AS item_count
                        FROM work_log w
                        JOIN orders o ON o.order_id = w.order_id
                        JOIN order_process_node n ON n.node_instance_id = w.node_instance_id
                        WHERE w.status = 'COMPLETED'
                          AND w.finished_at >= :startAt
                          AND w.finished_at < :endExclusive
                        """ + orderClause + """
                        GROUP BY n.stage_name, n.process_name, n.node_category, DATE(w.finished_at)
                        """)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive);
        if (orderNoPrefix != null) {
            spec = spec.param("orderNoPattern", orderNoPrefix + "%");
        }
        spec.query((rs, rowNum) -> {
                    WorkbenchDepartmentAccumulator accumulator = departments.get(workbenchDepartmentKey(rs));
                    accumulator.day(rs.getDate("production_date").toLocalDate()).completedWorkLogs += rs.getLong("item_count");
                    return null;
                })
                .list();
    }

    private void loadWorkbenchCompletedNodes(
            String orderNoPrefix,
            LocalDateTime startAt,
            LocalDateTime endExclusive,
            Map<String, WorkbenchDepartmentAccumulator> departments) {
        String orderClause = orderNoPrefix == null ? "" : " AND o.order_no LIKE :orderNoPattern\n";
        JdbcClient.StatementSpec spec = jdbcClient.sql("""
                        SELECT
                            COALESCE(n.stage_name, '') AS stage_name,
                            COALESCE(n.process_name, '') AS process_name,
                            COALESCE(n.node_category, '') AS node_category,
                            DATE(n.completed_at) AS production_date,
                            COUNT(*) AS item_count
                        FROM order_process_node n
                        JOIN order_process_instance pi ON pi.instance_id = n.instance_id
                        JOIN orders o ON o.order_id = pi.order_id
                        WHERE n.completed_at IS NOT NULL
                          AND n.completed_at >= :startAt
                          AND n.completed_at < :endExclusive
                        """ + orderClause + """
                        GROUP BY n.stage_name, n.process_name, n.node_category, DATE(n.completed_at)
                        """)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive);
        if (orderNoPrefix != null) {
            spec = spec.param("orderNoPattern", orderNoPrefix + "%");
        }
        spec.query((rs, rowNum) -> {
                    WorkbenchDepartmentAccumulator accumulator = departments.get(workbenchDepartmentKey(rs));
                    accumulator.day(rs.getDate("production_date").toLocalDate()).completedNodes += rs.getLong("item_count");
                    return null;
                })
                .list();
    }

    private void loadWorkbenchReworks(
            String orderNoPrefix,
            LocalDateTime startAt,
            LocalDateTime endExclusive,
            Map<String, WorkbenchDepartmentAccumulator> departments) {
        String orderClause = orderNoPrefix == null ? "" : " AND o.order_no LIKE :orderNoPattern\n";
        JdbcClient.StatementSpec spec = jdbcClient.sql("""
                        SELECT
                            COALESCE(n.stage_name, '') AS stage_name,
                            COALESCE(n.process_name, '') AS process_name,
                            COALESCE(n.node_category, '') AS node_category,
                            DATE(r.created_at) AS production_date,
                            COUNT(*) AS item_count
                        FROM rework_record r
                        JOIN orders o ON o.order_id = r.order_id
                        JOIN order_process_node n
                          ON n.node_instance_id = COALESCE(r.target_node_instance_id, r.from_node_instance_id)
                        WHERE r.created_at >= :startAt
                          AND r.created_at < :endExclusive
                        """ + orderClause + """
                        GROUP BY n.stage_name, n.process_name, n.node_category, DATE(r.created_at)
                        """)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive);
        if (orderNoPrefix != null) {
            spec = spec.param("orderNoPattern", orderNoPrefix + "%");
        }
        spec.query((rs, rowNum) -> {
                    WorkbenchDepartmentAccumulator accumulator = departments.get(workbenchDepartmentKey(rs));
                    accumulator.day(rs.getDate("production_date").toLocalDate()).reworkCount += rs.getLong("item_count");
                    return null;
                })
                .list();
    }

    private void loadWorkbenchActiveNodes(
            String orderNoPrefix,
            Map<String, WorkbenchDepartmentAccumulator> departments) {
        String orderClause = orderNoPrefix == null ? "" : " AND o.order_no LIKE :orderNoPattern\n";
        JdbcClient.StatementSpec spec = jdbcClient.sql("""
                        SELECT
                            COALESCE(n.stage_name, '') AS stage_name,
                            COALESCE(n.process_name, '') AS process_name,
                            COALESCE(n.node_category, '') AS node_category,
                            COUNT(*) AS item_count
                        FROM order_process_node n
                        JOIN order_process_instance pi ON pi.instance_id = n.instance_id
                        JOIN orders o ON o.order_id = pi.order_id
                        WHERE n.node_status IN ('READY', 'IN_PROGRESS')
                        """ + orderClause + """
                        GROUP BY n.stage_name, n.process_name, n.node_category
                        """);
        if (orderNoPrefix != null) {
            spec = spec.param("orderNoPattern", orderNoPrefix + "%");
        }
        spec.query((rs, rowNum) -> {
                    WorkbenchDepartmentAccumulator accumulator = departments.get(workbenchDepartmentKey(rs));
                    accumulator.activeNodes += rs.getLong("item_count");
                    return null;
                })
                .list();
    }

    private ProductionWorkbenchDepartmentSummaryResponse.DepartmentRow buildWorkbenchDepartmentRow(
            WorkbenchDepartmentDefinition department,
            WorkbenchDepartmentAccumulator accumulator,
            LocalDate today,
            YearMonth lastMonth,
            YearMonth currentMonth) {
        WorkbenchDayStats todayStats = accumulator.day(today);
        long todayTaskCount = todayStats.completedWorkLogs + accumulator.activeNodes;
        double todayCompletionRate = percentage(todayStats.completedWorkLogs, todayTaskCount);
        double todayReworkRate = percentage(todayStats.reworkCount, todayStats.completedWorkLogs);
        double todayShippingRate = percentage(todayStats.completedNodes, todayTaskCount);
        long lastMonthCompleted = accumulator.completedWorkLogs(lastMonth);
        long lastMonthCompletedDays = accumulator.completedWorkLogDays(lastMonth);
        long lastMonthDailyAverage = lastMonthCompletedDays == 0
                ? 0
                : Math.round(lastMonthCompleted / (double) lastMonthCompletedDays);
        double lastMonthReworkRate = percentage(accumulator.reworkCount(lastMonth), lastMonthCompleted);
        double lastMonthShippingRate = percentage(accumulator.completedNodes(lastMonth), lastMonthCompleted);
        WorkbenchStatus status = resolveWorkbenchStatus(
                todayCompletionRate,
                todayReworkRate,
                lastMonthReworkRate,
                todayShippingRate,
                lastMonthShippingRate,
                todayTaskCount,
                accumulator.completedWorkLogs(currentMonth));

        return new ProductionWorkbenchDepartmentSummaryResponse.DepartmentRow(
                department.key(),
                department.name(),
                department.subtitle(),
                department.displayOrder(),
                todayTaskCount,
                lastMonthDailyAverage,
                todayCompletionRate,
                todayReworkRate,
                lastMonthReworkRate,
                todayShippingRate,
                lastMonthShippingRate,
                status.code(),
                status.label());
    }

    private ProductionWorkbenchDepartmentSummaryResponse.DepartmentTrend buildAllWorkbenchTrend(
            Map<String, WorkbenchDepartmentAccumulator> departments,
            List<LocalDate> dates,
            LocalDate today) {
        List<ProductionWorkbenchDepartmentSummaryResponse.TrendPoint> points = dates.stream()
                .map(date -> {
                    WorkbenchDayStats stats = new WorkbenchDayStats();
                    long activeNodes = 0;
                    for (WorkbenchDepartmentAccumulator accumulator : departments.values()) {
                        WorkbenchDayStats departmentStats = accumulator.day(date);
                        stats.completedWorkLogs += departmentStats.completedWorkLogs;
                        stats.completedNodes += departmentStats.completedNodes;
                        stats.reworkCount += departmentStats.reworkCount;
                        activeNodes += accumulator.activeNodes;
                    }
                    return buildWorkbenchTrendPoint(date, stats, date.equals(today) ? activeNodes : 0);
                })
                .toList();
        return new ProductionWorkbenchDepartmentSummaryResponse.DepartmentTrend("ALL", "全部部门", points);
    }

    private ProductionWorkbenchDepartmentSummaryResponse.DepartmentTrend buildWorkbenchTrend(
            WorkbenchDepartmentDefinition department,
            WorkbenchDepartmentAccumulator accumulator,
            List<LocalDate> dates,
            LocalDate today) {
        List<ProductionWorkbenchDepartmentSummaryResponse.TrendPoint> points = dates.stream()
                .map(date -> buildWorkbenchTrendPoint(
                        date,
                        accumulator.day(date),
                        date.equals(today) ? accumulator.activeNodes : 0))
                .toList();
        return new ProductionWorkbenchDepartmentSummaryResponse.DepartmentTrend(
                department.key(), department.name(), points);
    }

    private ProductionWorkbenchDepartmentSummaryResponse.TrendPoint buildWorkbenchTrendPoint(
            LocalDate date,
            WorkbenchDayStats stats,
            long activeNodes) {
        long taskCount = stats.completedWorkLogs + activeNodes;
        return new ProductionWorkbenchDepartmentSummaryResponse.TrendPoint(
                date,
                percentage(stats.completedWorkLogs, taskCount),
                percentage(stats.reworkCount, stats.completedWorkLogs),
                percentage(stats.completedNodes, taskCount));
    }

    private WorkbenchStatus resolveWorkbenchStatus(
            double completionRate,
            double todayReworkRate,
            double lastMonthReworkRate,
            double todayShippingRate,
            double lastMonthShippingRate,
            long todayTaskCount,
            long currentMonthCompleted) {
        if (todayTaskCount == 0 && currentMonthCompleted == 0) {
            return new WorkbenchStatus("NORMAL", "正常");
        }
        int severity = 0;
        if (completionRate < 60.0) {
            severity = Math.max(severity, 3);
        } else if (completionRate < 70.0) {
            severity = Math.max(severity, 2);
        } else if (completionRate < 80.0) {
            severity = Math.max(severity, 1);
        }
        if (lastMonthReworkRate > 0.0) {
            if (todayReworkRate >= lastMonthReworkRate * 2.0) {
                severity = Math.max(severity, 3);
            } else if (todayReworkRate >= lastMonthReworkRate * 1.5) {
                severity = Math.max(severity, 2);
            } else if (todayReworkRate > lastMonthReworkRate) {
                severity = Math.max(severity, 1);
            }
        } else if (todayReworkRate > 0.0) {
            severity = Math.max(severity, 1);
        }
        double shippingDrop = lastMonthShippingRate - todayShippingRate;
        if (shippingDrop > 10.0) {
            severity = Math.max(severity, 3);
        } else if (shippingDrop > 5.0) {
            severity = Math.max(severity, 2);
        } else if (shippingDrop > 0.0) {
            severity = Math.max(severity, 1);
        }
        return switch (severity) {
            case 3 -> new WorkbenchStatus("RISK", "风险");
            case 2 -> new WorkbenchStatus("DISPATCH", "调度");
            case 1 -> new WorkbenchStatus("ATTENTION", "关注");
            default -> new WorkbenchStatus("NORMAL", "正常");
        };
    }

    private String workbenchDepartmentKey(ResultSet rs) throws SQLException {
        return workbenchDepartmentKey(
                rs.getString("stage_name"),
                rs.getString("process_name"),
                rs.getString("node_category"));
    }

    private String workbenchDepartmentKey(String stageName, String processName, String nodeCategory) {
        String text = ((stageName == null ? "" : stageName)
                        + " "
                        + (processName == null ? "" : processName)
                        + " "
                        + (nodeCategory == null ? "" : nodeCategory))
                .toUpperCase(Locale.ROOT);
        if (text.contains("质检") || text.contains("检验") || text.contains("终检") || text.contains("CHECK")) {
            return "QC";
        }
        if (text.contains("发货") || text.contains("出货") || text.contains("物流") || text.contains("包装")) {
            return "DISPATCH";
        }
        if (text.contains("3D") || text.contains("打印")) {
            return "PRINTING_3D";
        }
        if (text.contains("种植") || text.contains("IMPLANT")) {
            return "IMPLANT";
        }
        if (text.contains("钢托") || text.contains("钢架") || text.contains("STEEL")) {
            return "STEEL_FRAMEWORK";
        }
        if (text.contains("胶托") || text.contains("ACRYLIC")) {
            return "ACRYLIC";
        }
        if (text.contains("隐形") || text.contains("FLEXIBLE")) {
            return "FLEXIBLE";
        }
        if (text.contains("正畸") || text.contains("ORTHO")) {
            return "ORTHO";
        }
        if (text.contains("车瓷") || text.contains("PORCELAIN")) {
            return "PORCELAIN";
        }
        if (text.contains("上瓷") || text.contains("上釉") || text.contains("烧结") || text.contains("STAIN")) {
            return "STAINING";
        }
        if (text.contains("车金") || text.contains("切削") || text.contains("研磨") || text.contains("MILL")) {
            return "MILLING";
        }
        if (text.contains("CAD") || text.contains("设计") || text.contains("排版") || text.contains("DESIGN")) {
            return "CAD";
        }
        return "DATA_REVIEW";
    }

    public ProductionEquipmentSummaryResponse getProductionEquipmentSummary(
            String equipmentCodePrefix, BootstrapIdentity identity) {
        accessControlService.requireCheckRecordRead(identity);
        String normalizedPrefix = blankToNull(equipmentCodePrefix);
        String prefixClause = normalizedPrefix == null ? "" : " WHERE e.equipment_code LIKE :equipmentCodePattern";

        JdbcClient.StatementSpec equipmentSpec = jdbcClient.sql("""
                        SELECT
                            COUNT(*) AS total_equipment_count,
                            COALESCE(SUM(CASE WHEN e.status = 'RUNNING' THEN 1 ELSE 0 END), 0) AS running_count,
                            COALESCE(SUM(CASE WHEN e.status = 'IDLE' THEN 1 ELSE 0 END), 0) AS idle_count,
                            COALESCE(SUM(CASE WHEN e.status = 'MAINTENANCE' THEN 1 ELSE 0 END), 0) AS maintenance_count,
                            COALESCE(SUM(CASE WHEN e.status = 'FAULT' THEN 1 ELSE 0 END), 0) AS fault_count,
                            COALESCE(AVG(e.utilization_rate), 0) AS average_utilization_rate
                        FROM production_equipment e
                        """ + prefixClause);
        if (normalizedPrefix != null) {
            equipmentSpec = equipmentSpec.param("equipmentCodePattern", normalizedPrefix + "%");
        }
        EquipmentSummaryRow equipmentSummary = equipmentSpec.query((rs, rowNum) -> new EquipmentSummaryRow(
                        rs.getLong("total_equipment_count"),
                        rs.getLong("running_count"),
                        rs.getLong("idle_count"),
                        rs.getLong("maintenance_count"),
                        rs.getLong("fault_count"),
                        roundedDecimal(rs.getBigDecimal("average_utilization_rate"))))
                .single();

        String eventPrefixClause = normalizedPrefix == null ? "" : " WHERE e.equipment_code LIKE :equipmentCodePattern";
        JdbcClient.StatementSpec eventSpec = jdbcClient.sql("""
                        SELECT
                            COALESCE(SUM(CASE
                                WHEN ev.event_type = 'MAINTENANCE_PLAN'
                                     AND ev.status IN ('PENDING', 'IN_PROGRESS')
                                THEN 1 ELSE 0 END), 0) AS pending_maintenance_count,
                            COALESCE(SUM(CASE
                                WHEN ev.event_type = 'FAULT_REPAIR'
                                     AND ev.status IN ('PENDING', 'IN_PROGRESS')
                                THEN 1 ELSE 0 END), 0) AS open_fault_count,
                            COALESCE(SUM(ev.downtime_minutes), 0) AS downtime_minutes
                        FROM production_equipment_event ev
                        JOIN production_equipment e ON e.equipment_id = ev.equipment_id
                        """ + eventPrefixClause);
        if (normalizedPrefix != null) {
            eventSpec = eventSpec.param("equipmentCodePattern", normalizedPrefix + "%");
        }
        EquipmentEventSummaryRow eventSummary = eventSpec.query((rs, rowNum) -> new EquipmentEventSummaryRow(
                        rs.getLong("pending_maintenance_count"),
                        rs.getLong("open_fault_count"),
                        rs.getLong("downtime_minutes")))
                .single();

        return new ProductionEquipmentSummaryResponse(
                normalizedPrefix,
                equipmentSummary.totalEquipmentCount(),
                equipmentSummary.runningCount(),
                equipmentSummary.idleCount(),
                equipmentSummary.maintenanceCount(),
                equipmentSummary.faultCount(),
                eventSummary.pendingMaintenanceCount(),
                eventSummary.openFaultCount(),
                eventSummary.downtimeMinutes(),
                equipmentSummary.averageUtilizationRate(),
                LocalDateTime.now());
    }

    public List<ProductionEquipmentResponse> listProductionEquipment(
            String keyword, String status, BootstrapIdentity identity) {
        accessControlService.requireCheckRecordRead(identity);
        String normalizedKeyword = blankToNull(keyword);
        String normalizedStatus = blankToNull(status);
        if (normalizedStatus != null) {
            normalizedStatus = normalizeEquipmentStatus(normalizedStatus);
        }
        JdbcClient.StatementSpec spec = jdbcClient.sql("""
                        SELECT equipment_id, equipment_code, equipment_name, equipment_type, department_name,
                               status, owner_user_id, utilization_rate, last_maintenance_at,
                               next_maintenance_at, created_at, updated_at
                        FROM production_equipment
                        WHERE (:keyword IS NULL
                               OR equipment_code LIKE :keywordPattern
                               OR equipment_name LIKE :keywordPattern
                               OR equipment_type LIKE :keywordPattern
                               OR department_name LIKE :keywordPattern)
                          AND (:status IS NULL OR status = :status)
                        ORDER BY updated_at DESC, equipment_id DESC
                        LIMIT 200
                        """)
                .param("keyword", normalizedKeyword)
                .param("keywordPattern", normalizedKeyword == null ? null : "%" + normalizedKeyword + "%")
                .param("status", normalizedStatus);
        return spec.query(this::mapProductionEquipment).list();
    }

    public ProductionEquipmentDetailResponse getProductionEquipment(
            String equipmentCode, BootstrapIdentity identity) {
        accessControlService.requireCheckRecordRead(identity);
        ProductionEquipmentResponse equipment = loadProductionEquipment(normalizeEquipmentCode(equipmentCode));
        List<ProductionEquipmentEventResponse> events = jdbcClient.sql("""
                        SELECT ev.event_id, ev.equipment_id, e.equipment_code, ev.event_type, ev.status,
                               ev.downtime_minutes, ev.description, ev.requested_by_user_id,
                               ev.approved_by_user_id, ev.decision_note, ev.decided_at,
                               ev.created_at, ev.resolved_at
                        FROM production_equipment_event ev
                        JOIN production_equipment e ON e.equipment_id = ev.equipment_id
                        WHERE ev.equipment_id = :equipmentId
                        ORDER BY ev.created_at DESC, ev.event_id DESC
                        """)
                .param("equipmentId", equipment.equipmentId())
                .query(this::mapProductionEquipmentEvent)
                .list();
        return new ProductionEquipmentDetailResponse(equipment, events);
    }

    public List<ProductionEquipmentEventResponse> listProductionEquipmentApprovals(
            String status, BootstrapIdentity identity) {
        accessControlService.requireCheckRecordRead(identity);
        String normalizedStatus = blankToNull(status);
        if (normalizedStatus != null) {
            normalizedStatus = normalizeEquipmentEventStatus(normalizedStatus);
        }
        return jdbcClient.sql("""
                        SELECT ev.event_id, ev.equipment_id, e.equipment_code, ev.event_type, ev.status,
                               ev.downtime_minutes, ev.description, ev.requested_by_user_id,
                               ev.approved_by_user_id, ev.decision_note, ev.decided_at,
                               ev.created_at, ev.resolved_at
                        FROM production_equipment_event ev
                        JOIN production_equipment e ON e.equipment_id = ev.equipment_id
                        WHERE ev.event_type IN ('REPAIR_REQUEST', 'SCRAP_REQUEST')
                          AND (:status IS NULL OR ev.status = :status)
                        ORDER BY CASE WHEN ev.status = 'PENDING' THEN 0 ELSE 1 END,
                                 ev.created_at DESC, ev.event_id DESC
                        LIMIT 200
                        """)
                .param("status", normalizedStatus)
                .query(this::mapProductionEquipmentEvent)
                .list();
    }

    @Transactional
    public ProductionEquipmentEventResponse decideProductionEquipmentApproval(
            long eventId, ProductionEquipmentApprovalRequest request, BootstrapIdentity identity) {
        requirePermission(identity, "production:equipment:approve", "equipment approval requires production:equipment:approve");
        String decision = normalizeRequired(request.decision(), "decision").toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVED", "REJECTED").contains(decision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported equipment approval decision");
        }
        String decisionNote = normalizeOptionalDescription(request.decisionNote());
        int updated = jdbcClient.sql("""
                        UPDATE production_equipment_event
                        SET status = :decision,
                            approved_by_user_id = :approvedByUserId,
                            decision_note = :decisionNote,
                            decided_at = CURRENT_TIMESTAMP(3),
                            resolved_at = CURRENT_TIMESTAMP(3)
                        WHERE event_id = :eventId
                          AND event_type IN ('REPAIR_REQUEST', 'SCRAP_REQUEST')
                          AND status = 'PENDING'
                        """)
                .param("eventId", eventId)
                .param("decision", decision)
                .param("approvedByUserId", identity.userId())
                .param("decisionNote", decisionNote)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "equipment approval is not pending");
        }
        if ("APPROVED".equals(decision)) {
            jdbcClient.sql("""
                            UPDATE production_equipment e
                            JOIN production_equipment_event ev ON ev.equipment_id = e.equipment_id
                            SET e.status = CASE
                                    WHEN ev.event_type = 'SCRAP_REQUEST' THEN 'SCRAPPED'
                                    ELSE 'MAINTENANCE'
                                END
                            WHERE ev.event_id = :eventId
                            """)
                    .param("eventId", eventId)
                    .update();
        }
        return loadProductionEquipmentEvent(eventId);
    }

    @Transactional
    public ProductionEquipmentResponse createProductionEquipment(
            ProductionEquipmentRequest request, BootstrapIdentity identity) {
        requireProductionEquipmentWrite(identity);
        EquipmentInput input = normalizeProductionEquipment(request);
        try {
            jdbcClient.sql("""
                            INSERT INTO production_equipment
                                (equipment_code, equipment_name, equipment_type, department_name,
                                 status, owner_user_id, utilization_rate)
                            VALUES
                                (:equipmentCode, :equipmentName, :equipmentType, :departmentName,
                                 :status, :ownerUserId, :utilizationRate)
                            """)
                    .param("equipmentCode", input.equipmentCode())
                    .param("equipmentName", input.equipmentName())
                    .param("equipmentType", input.equipmentType())
                    .param("departmentName", input.departmentName())
                    .param("status", input.status())
                    .param("ownerUserId", identity.userId())
                    .param("utilizationRate", input.utilizationRate())
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "equipment_code already exists", ex);
        }
        return loadProductionEquipment(lastInsertId());
    }

    @Transactional
    public ProductionEquipmentEventResponse createProductionEquipmentEvent(
            String equipmentCode, ProductionEquipmentEventRequest request, BootstrapIdentity identity) {
        requireProductionEquipmentWrite(identity);
        String normalizedEquipmentCode = normalizeEquipmentCode(equipmentCode);
        EquipmentEventInput input = normalizeProductionEquipmentEvent(request);
        if (EQUIPMENT_APPROVAL_TYPES.contains(input.eventType()) && !"PENDING".equals(input.status())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "repair or scrap request must be created as PENDING and decided through the approval endpoint");
        }
        long equipmentId = findEquipmentIdByCode(normalizedEquipmentCode);
        jdbcClient.sql("""
                        INSERT INTO production_equipment_event
                            (equipment_id, event_type, status, downtime_minutes, description,
                             requested_by_user_id, resolved_at)
                        VALUES
                            (:equipmentId, :eventType, :status, :downtimeMinutes, :description,
                             :requestedByUserId, :resolvedAt)
                        """)
                .param("equipmentId", equipmentId)
                .param("eventType", input.eventType())
                .param("status", input.status())
                .param("downtimeMinutes", input.downtimeMinutes())
                .param("description", input.description())
                .param("requestedByUserId", identity.userId())
                .param("resolvedAt", "DONE".equals(input.status()) ? LocalDateTime.now() : null)
                .update();
        return loadProductionEquipmentEvent(lastInsertId());
    }

    public ProductionMaterialExceptionSummaryResponse getProductionMaterialExceptionSummary(
            String exceptionNoPrefix, BootstrapIdentity identity) {
        accessControlService.requireCheckRecordRead(identity);
        String normalizedPrefix = blankToNull(exceptionNoPrefix);
        String prefixClause = normalizedPrefix == null ? "" : " AND m.exception_no LIKE :exceptionNoPattern";
        YearMonth currentMonth = YearMonth.now(WORKBENCH_ZONE);
        LocalDateTime currentMonthStart = currentMonth.atDay(1).atStartOfDay();
        LocalDateTime nextMonthStart = currentMonth.plusMonths(1).atDay(1).atStartOfDay();
        LocalDateTime previousMonthStart = currentMonth.minusMonths(1).atDay(1).atStartOfDay();

        JdbcClient.StatementSpec spec = jdbcClient.sql("""
                        SELECT
                            COUNT(*) AS total_exception_count,
                            COALESCE(SUM(CASE
                                WHEN m.created_at >= :currentMonthStart AND m.created_at < :nextMonthStart
                                THEN 1 ELSE 0 END), 0) AS current_month_count,
                            COALESCE(SUM(CASE
                                WHEN m.created_at >= :previousMonthStart AND m.created_at < :currentMonthStart
                                THEN 1 ELSE 0 END), 0) AS previous_month_count,
                            COALESCE(SUM(CASE WHEN m.exception_type = 'SHORTAGE' THEN 1 ELSE 0 END), 0)
                                AS shortage_count,
                            COALESCE(SUM(CASE WHEN m.exception_type = 'WRONG_MATERIAL' THEN 1 ELSE 0 END), 0)
                                AS wrong_material_count,
                            COALESCE(SUM(CASE WHEN m.exception_type = 'BATCH_ABNORMAL' THEN 1 ELSE 0 END), 0)
                                AS batch_abnormal_count,
                            COALESCE(SUM(CASE WHEN m.exception_type = 'MATERIAL_LOSS' THEN 1 ELSE 0 END), 0)
                                AS material_loss_count,
                            COALESCE(SUM(CASE WHEN m.status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_count,
                            COALESCE(SUM(CASE WHEN m.status = 'IN_PROGRESS' THEN 1 ELSE 0 END), 0)
                                AS in_progress_count,
                            COALESCE(SUM(CASE WHEN m.status = 'CLOSED' THEN 1 ELSE 0 END), 0) AS closed_count,
                            COALESCE(SUM(CASE
                                WHEN m.responsibility_owner IS NOT NULL AND m.responsibility_owner <> ''
                                THEN 1 ELSE 0 END), 0) AS responsibility_assigned_count,
                            COALESCE(SUM(m.loss_quantity), 0) AS total_loss_quantity
                        FROM production_material_exception m
                        WHERE 1 = 1
                        """ + prefixClause);
        spec = spec
                .param("currentMonthStart", currentMonthStart)
                .param("nextMonthStart", nextMonthStart)
                .param("previousMonthStart", previousMonthStart);
        if (normalizedPrefix != null) {
            spec = spec.param("exceptionNoPattern", normalizedPrefix + "%");
        }
        MaterialExceptionSummaryRow summary = spec.query((rs, rowNum) -> new MaterialExceptionSummaryRow(
                        rs.getLong("total_exception_count"),
                        rs.getLong("current_month_count"),
                        rs.getLong("previous_month_count"),
                        rs.getLong("shortage_count"),
                        rs.getLong("wrong_material_count"),
                        rs.getLong("batch_abnormal_count"),
                        rs.getLong("material_loss_count"),
                        rs.getLong("pending_count"),
                        rs.getLong("in_progress_count"),
                        rs.getLong("closed_count"),
                        rs.getLong("responsibility_assigned_count"),
                        roundedDecimal(rs.getBigDecimal("total_loss_quantity"), 2)))
                .single();

        return new ProductionMaterialExceptionSummaryResponse(
                normalizedPrefix,
                summary.totalExceptionCount(),
                summary.currentMonthCount(),
                summary.previousMonthCount(),
                summary.shortageCount(),
                summary.wrongMaterialCount(),
                summary.batchAbnormalCount(),
                summary.materialLossCount(),
                summary.pendingCount(),
                summary.inProgressCount(),
                summary.closedCount(),
                summary.responsibilityAssignedCount(),
                summary.totalLossQuantity(),
                LocalDateTime.now());
    }

    public List<ProductionMaterialExceptionResponse> listProductionMaterialExceptions(
            String keyword, String status, BootstrapIdentity identity) {
        accessControlService.requireCheckRecordRead(identity);
        String normalizedKeyword = blankToNull(keyword);
        String normalizedStatus = blankToNull(status);
        if (normalizedStatus != null) {
            normalizedStatus = normalizeMaterialExceptionStatus(normalizedStatus);
        }
        return jdbcClient.sql("""
                        SELECT exception_id, exception_no, material_code, material_name, order_id, node_instance_id,
                               exception_type, status, responsibility_owner, loss_quantity, description,
                               created_at, updated_at, closed_at
                        FROM production_material_exception
                        WHERE (:keyword IS NULL
                               OR exception_no LIKE :keywordPattern
                               OR material_code LIKE :keywordPattern
                               OR material_name LIKE :keywordPattern
                               OR responsibility_owner LIKE :keywordPattern)
                          AND (:status IS NULL OR status = :status)
                        ORDER BY updated_at DESC, exception_id DESC
                        LIMIT 200
                        """)
                .param("keyword", normalizedKeyword)
                .param("keywordPattern", normalizedKeyword == null ? null : "%" + normalizedKeyword + "%")
                .param("status", normalizedStatus)
                .query(this::mapProductionMaterialException)
                .list();
    }

    public ProductionMaterialExceptionResponse getProductionMaterialException(
            String exceptionNo, BootstrapIdentity identity) {
        accessControlService.requireCheckRecordRead(identity);
        return loadProductionMaterialException(normalizeExceptionNo(exceptionNo));
    }

    @Transactional
    public ProductionMaterialExceptionResponse createProductionMaterialException(
            ProductionMaterialExceptionRequest request, BootstrapIdentity identity) {
        requireProductionMaterialExceptionWrite(identity);
        MaterialExceptionInput input = normalizeProductionMaterialException(request);
        try {
            jdbcClient.sql("""
                            INSERT INTO production_material_exception
                                (exception_no, material_code, material_name, order_id, node_instance_id,
                                 exception_type, status, responsibility_owner, loss_quantity, description, closed_at)
                            VALUES
                                (:exceptionNo, :materialCode, :materialName, :orderId, :nodeInstanceId,
                                 :exceptionType, :status, :responsibilityOwner, :lossQuantity, :description, :closedAt)
                            """)
                    .param("exceptionNo", input.exceptionNo())
                    .param("materialCode", input.materialCode())
                    .param("materialName", input.materialName())
                    .param("orderId", input.orderId())
                    .param("nodeInstanceId", input.nodeInstanceId())
                    .param("exceptionType", input.exceptionType())
                    .param("status", input.status())
                    .param("responsibilityOwner", input.responsibilityOwner())
                    .param("lossQuantity", input.lossQuantity())
                    .param("description", input.description())
                    .param("closedAt", "CLOSED".equals(input.status()) ? LocalDateTime.now() : null)
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "exception_no already exists", ex);
        }
        return loadProductionMaterialException(lastInsertId());
    }

    @Transactional
    public ProductionMaterialExceptionResponse updateProductionMaterialExceptionStatus(
            String exceptionNo, ProductionMaterialExceptionStatusRequest request, BootstrapIdentity identity) {
        requireProductionMaterialExceptionWrite(identity);
        String normalizedExceptionNo = normalizeExceptionNo(exceptionNo);
        String normalizedStatus = normalizeMaterialExceptionStatus(request.status());
        String responsibilityOwner = blankToNull(request.responsibilityOwner());
        String description = normalizeOptionalDescription(request.description());
        int updated = jdbcClient.sql("""
                        UPDATE production_material_exception
                        SET status = :status,
                            responsibility_owner = COALESCE(:responsibilityOwner, responsibility_owner),
                            description = COALESCE(:description, description),
                            closed_at = CASE WHEN :status = 'CLOSED' THEN CURRENT_TIMESTAMP(3) ELSE NULL END
                        WHERE exception_no = :exceptionNo
                        """)
                .param("exceptionNo", normalizedExceptionNo)
                .param("status", normalizedStatus)
                .param("responsibilityOwner", responsibilityOwner)
                .param("description", description)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "material exception not found");
        }
        return loadProductionMaterialException(normalizedExceptionNo);
    }

    public ProductionSafetyEnvironmentSummaryResponse getProductionSafetyEnvironmentSummary(
            String eventNoPrefix, BootstrapIdentity identity) {
        accessControlService.requireCheckRecordRead(identity);
        String normalizedPrefix = blankToNull(eventNoPrefix);
        String prefixClause = normalizedPrefix == null ? "" : " WHERE s.event_no LIKE :eventNoPattern";

        JdbcClient.StatementSpec spec = jdbcClient.sql("""
                        SELECT
                            COUNT(*) AS total_event_count,
                            COALESCE(SUM(CASE WHEN s.event_type = 'SAFETY_INSPECTION' THEN 1 ELSE 0 END), 0)
                                AS safety_inspection_count,
                            COALESCE(SUM(CASE WHEN s.event_type = 'HAZARD_RECTIFICATION' THEN 1 ELSE 0 END), 0)
                                AS hazard_rectification_count,
                            COALESCE(SUM(CASE WHEN s.event_type = 'ENVIRONMENT_RECORD' THEN 1 ELSE 0 END), 0)
                                AS environment_record_count,
                            COALESCE(SUM(CASE WHEN s.event_type = 'PPE_DEVICE_REMINDER' THEN 1 ELSE 0 END), 0)
                                AS ppe_device_reminder_count,
                            COALESCE(SUM(CASE WHEN s.status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_count,
                            COALESCE(SUM(CASE WHEN s.status = 'IN_PROGRESS' THEN 1 ELSE 0 END), 0)
                                AS in_progress_count,
                            COALESCE(SUM(CASE WHEN s.status = 'CLOSED' THEN 1 ELSE 0 END), 0) AS closed_count,
                            COALESCE(SUM(CASE
                                WHEN s.status <> 'CLOSED'
                                     AND s.due_at IS NOT NULL
                                     AND s.due_at < CURRENT_TIMESTAMP(3)
                                THEN 1 ELSE 0 END), 0) AS overdue_count,
                            COALESCE(SUM(CASE
                                WHEN s.risk_level IN ('HIGH', 'CRITICAL') THEN 1 ELSE 0 END), 0)
                                AS high_risk_count
                        FROM production_safety_event s
                        """ + prefixClause);
        if (normalizedPrefix != null) {
            spec = spec.param("eventNoPattern", normalizedPrefix + "%");
        }
        SafetyEnvironmentSummaryRow summary = spec.query((rs, rowNum) -> new SafetyEnvironmentSummaryRow(
                        rs.getLong("total_event_count"),
                        rs.getLong("safety_inspection_count"),
                        rs.getLong("hazard_rectification_count"),
                        rs.getLong("environment_record_count"),
                        rs.getLong("ppe_device_reminder_count"),
                        rs.getLong("pending_count"),
                        rs.getLong("in_progress_count"),
                        rs.getLong("closed_count"),
                        rs.getLong("overdue_count"),
                        rs.getLong("high_risk_count")))
                .single();

        return new ProductionSafetyEnvironmentSummaryResponse(
                normalizedPrefix,
                summary.totalEventCount(),
                summary.safetyInspectionCount(),
                summary.hazardRectificationCount(),
                summary.environmentRecordCount(),
                summary.ppeDeviceReminderCount(),
                summary.pendingCount(),
                summary.inProgressCount(),
                summary.closedCount(),
                summary.overdueCount(),
                summary.highRiskCount(),
                LocalDateTime.now());
    }

    public List<ProductionSafetyEnvironmentEventResponse> listProductionSafetyEnvironmentEvents(
            String keyword, String status, BootstrapIdentity identity) {
        accessControlService.requireCheckRecordRead(identity);
        String normalizedKeyword = blankToNull(keyword);
        String normalizedStatus = blankToNull(status);
        if (normalizedStatus != null) {
            normalizedStatus = normalizeSafetyEnvironmentEventStatus(normalizedStatus);
        }
        return jdbcClient.sql("""
                        SELECT event_id, event_no, event_type, status, department_name, responsible_owner,
                               equipment_code, risk_level, due_at, description, created_at, updated_at, closed_at
                        FROM production_safety_event
                        WHERE (:keyword IS NULL
                               OR event_no LIKE :keywordPattern
                               OR department_name LIKE :keywordPattern
                               OR responsible_owner LIKE :keywordPattern
                               OR description LIKE :keywordPattern)
                          AND (:status IS NULL OR status = :status)
                        ORDER BY CASE WHEN status = 'CLOSED' THEN 1 ELSE 0 END,
                                 due_at ASC, updated_at DESC, event_id DESC
                        LIMIT 200
                        """)
                .param("keyword", normalizedKeyword)
                .param("keywordPattern", normalizedKeyword == null ? null : "%" + normalizedKeyword + "%")
                .param("status", normalizedStatus)
                .query(this::mapProductionSafetyEnvironmentEvent)
                .list();
    }

    public ProductionSafetyEnvironmentEventResponse getProductionSafetyEnvironmentEvent(
            String eventNo, BootstrapIdentity identity) {
        accessControlService.requireCheckRecordRead(identity);
        return loadProductionSafetyEnvironmentEvent(normalizeSafetyEnvironmentEventNo(eventNo));
    }

    public List<ProductionSafetyRuleResponse> listProductionSafetyRules(BootstrapIdentity identity) {
        accessControlService.requireCheckRecordRead(identity);
        return jdbcClient.sql("""
                        SELECT rule_id, rule_code, rule_name, check_type, department_name, cycle_type,
                               cycle_interval, responsible_owner, next_due_at, status, created_at, updated_at
                        FROM production_safety_rule
                        ORDER BY CASE WHEN status = 'ACTIVE' THEN 0 ELSE 1 END,
                                 next_due_at ASC, rule_id ASC
                        LIMIT 200
                        """)
                .query((rs, rowNum) -> new ProductionSafetyRuleResponse(
                        rs.getLong("rule_id"),
                        rs.getString("rule_code"),
                        rs.getString("rule_name"),
                        rs.getString("check_type"),
                        rs.getString("department_name"),
                        rs.getString("cycle_type"),
                        rs.getInt("cycle_interval"),
                        rs.getString("responsible_owner"),
                        rs.getObject("next_due_at", LocalDateTime.class),
                        rs.getString("status"),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("updated_at", LocalDateTime.class)))
                .list();
    }

    @Transactional
    public ProductionSafetyEnvironmentEventResponse createProductionSafetyEnvironmentEvent(
            ProductionSafetyEnvironmentEventRequest request, BootstrapIdentity identity) {
        requireProductionSafetyEnvironmentWrite(identity);
        SafetyEnvironmentEventInput input = normalizeProductionSafetyEnvironmentEvent(request);
        try {
            jdbcClient.sql("""
                            INSERT INTO production_safety_event
                                (event_no, event_type, status, department_name, responsible_owner,
                                 equipment_code, risk_level, due_at, description, closed_at)
                            VALUES
                                (:eventNo, :eventType, :status, :departmentName, :responsibleOwner,
                                 :equipmentCode, :riskLevel, :dueAt, :description, :closedAt)
                            """)
                    .param("eventNo", input.eventNo())
                    .param("eventType", input.eventType())
                    .param("status", input.status())
                    .param("departmentName", input.departmentName())
                    .param("responsibleOwner", input.responsibleOwner())
                    .param("equipmentCode", input.equipmentCode())
                    .param("riskLevel", input.riskLevel())
                    .param("dueAt", input.dueAt())
                    .param("description", input.description())
                    .param("closedAt", "CLOSED".equals(input.status()) ? LocalDateTime.now() : null)
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "event_no already exists", ex);
        }
        return loadProductionSafetyEnvironmentEvent(lastInsertId());
    }

    @Transactional
    public ProductionSafetyEnvironmentEventResponse updateProductionSafetyEnvironmentEventStatus(
            String eventNo, ProductionSafetyEnvironmentEventStatusRequest request, BootstrapIdentity identity) {
        requireProductionSafetyEnvironmentWrite(identity);
        String normalizedEventNo = normalizeSafetyEnvironmentEventNo(eventNo);
        String normalizedStatus = normalizeSafetyEnvironmentEventStatus(request.status());
        String responsibleOwner = blankToNull(request.responsibleOwner());
        String description = normalizeOptionalDescription(request.description());
        int updated = jdbcClient.sql("""
                        UPDATE production_safety_event
                        SET status = :status,
                            responsible_owner = COALESCE(:responsibleOwner, responsible_owner),
                            description = COALESCE(:description, description),
                            closed_at = CASE WHEN :status = 'CLOSED' THEN CURRENT_TIMESTAMP(3) ELSE NULL END
                        WHERE event_no = :eventNo
                        """)
                .param("eventNo", normalizedEventNo)
                .param("status", normalizedStatus)
                .param("responsibleOwner", responsibleOwner)
                .param("description", description)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "safety event not found");
        }
        return loadProductionSafetyEnvironmentEvent(normalizedEventNo);
    }

    public ProductionCostSummaryResponse getProductionCostSummary(String costNoPrefix, BootstrapIdentity identity) {
        accessControlService.requireCheckRecordRead(identity);
        String normalizedPrefix = blankToNull(costNoPrefix);
        String prefixClause = normalizedPrefix == null ? "" : " WHERE c.cost_no LIKE :costNoPattern";

        JdbcClient.StatementSpec spec = jdbcClient.sql("""
                        SELECT
                            COUNT(*) AS record_count,
                            COALESCE(SUM(c.amount), 0) AS total_cost_amount,
                            COALESCE(SUM(CASE WHEN c.cost_type = 'PROCESS' THEN c.amount ELSE 0 END), 0)
                                AS process_cost_amount,
                            COALESCE(SUM(CASE WHEN c.cost_type = 'MATERIAL' THEN c.amount ELSE 0 END), 0)
                                AS material_cost_amount,
                            COALESCE(SUM(CASE WHEN c.cost_type = 'LABOR' THEN c.amount ELSE 0 END), 0)
                                AS labor_cost_amount,
                            COALESCE(SUM(CASE WHEN c.cost_type = 'REWORK' THEN c.amount ELSE 0 END), 0)
                                AS rework_cost_amount,
                            COALESCE(SUM(CASE WHEN c.cost_type = 'OUTSOURCING' THEN c.amount ELSE 0 END), 0)
                                AS outsourcing_cost_amount,
                            COALESCE(SUM(CASE WHEN c.status = 'WARNING' THEN 1 ELSE 0 END), 0)
                                AS abnormal_warning_count
                        FROM production_cost_record c
                        """ + prefixClause);
        if (normalizedPrefix != null) {
            spec = spec.param("costNoPattern", normalizedPrefix + "%");
        }
        CostSummaryRow summary = spec.query((rs, rowNum) -> new CostSummaryRow(
                        rs.getLong("record_count"),
                        roundedDecimal(rs.getBigDecimal("total_cost_amount"), 2),
                        roundedDecimal(rs.getBigDecimal("process_cost_amount"), 2),
                        roundedDecimal(rs.getBigDecimal("material_cost_amount"), 2),
                        roundedDecimal(rs.getBigDecimal("labor_cost_amount"), 2),
                        roundedDecimal(rs.getBigDecimal("rework_cost_amount"), 2),
                        roundedDecimal(rs.getBigDecimal("outsourcing_cost_amount"), 2),
                        rs.getLong("abnormal_warning_count")))
                .single();

        return new ProductionCostSummaryResponse(
                normalizedPrefix,
                summary.recordCount(),
                summary.totalCostAmount(),
                summary.processCostAmount(),
                summary.materialCostAmount(),
                summary.laborCostAmount(),
                summary.reworkCostAmount(),
                summary.outsourcingCostAmount(),
                summary.abnormalWarningCount(),
                LocalDateTime.now());
    }

    public List<ProductionCostRecordResponse> listProductionCostRecords(
            String keyword, String status, BootstrapIdentity identity) {
        accessControlService.requireCheckRecordRead(identity);
        String normalizedKeyword = blankToNull(keyword);
        String normalizedStatus = blankToNull(status);
        if (normalizedStatus != null) {
            normalizedStatus = normalizeProductionCostStatus(normalizedStatus);
        }
        return jdbcClient.sql("""
                        SELECT cost_id, cost_no, order_id, node_instance_id, cost_type, amount, status,
                               department_name, supplier_name, description, created_at, updated_at, confirmed_at
                        FROM production_cost_record
                        WHERE (:keyword IS NULL
                               OR cost_no LIKE :keywordPattern
                               OR CAST(order_id AS CHAR) LIKE :keywordPattern
                               OR department_name LIKE :keywordPattern
                               OR supplier_name LIKE :keywordPattern
                               OR description LIKE :keywordPattern)
                          AND (:status IS NULL OR status = :status)
                        ORDER BY CASE WHEN status = 'WARNING' THEN 0 ELSE 1 END,
                                 updated_at DESC, cost_id DESC
                        LIMIT 300
                        """)
                .param("keyword", normalizedKeyword)
                .param("keywordPattern", normalizedKeyword == null ? null : "%" + normalizedKeyword + "%")
                .param("status", normalizedStatus)
                .query(this::mapProductionCostRecord)
                .list();
    }

    public ProductionCostRecordResponse getProductionCostRecord(String costNo, BootstrapIdentity identity) {
        accessControlService.requireCheckRecordRead(identity);
        return loadProductionCostRecord(normalizeCostNo(costNo));
    }

    @Transactional
    public ProductionCostRecordResponse updateProductionCostRecordStatus(
            String costNo, ProductionCostStatusRequest request, BootstrapIdentity identity) {
        requirePermission(identity, "production:cost:confirm", "cost confirmation requires production:cost:confirm");
        String normalizedCostNo = normalizeCostNo(costNo);
        String normalizedStatus = normalizeProductionCostStatus(request.status());
        int updated = jdbcClient.sql("""
                        UPDATE production_cost_record
                        SET status = :status,
                            confirmed_at = CASE WHEN :status = 'CONFIRMED' THEN CURRENT_TIMESTAMP(3) ELSE NULL END
                        WHERE cost_no = :costNo
                        """)
                .param("costNo", normalizedCostNo)
                .param("status", normalizedStatus)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "cost record not found");
        }
        return loadProductionCostRecord(normalizedCostNo);
    }

    public List<ProductionOutsourcingBatchResponse> listProductionOutsourcing(
            String keyword, String status, BootstrapIdentity identity) {
        accessControlService.requireCheckRecordRead(identity);
        String normalizedKeyword = blankToNull(keyword);
        String normalizedStatus = blankToNull(status);
        return outsourcingQuery("""
                        WHERE (:keyword IS NULL
                               OR b.batch_no LIKE :keywordPattern
                               OR o.order_no LIKE :keywordPattern
                               OR b.item_name LIKE :keywordPattern
                               OR b.supplier_name LIKE :keywordPattern)
                          AND (:status IS NULL OR b.status = :status)
                        ORDER BY CASE
                                   WHEN b.status <> 'RETURNED'
                                        AND b.expected_return_at IS NOT NULL
                                        AND b.expected_return_at < CURRENT_TIMESTAMP(3) THEN 0
                                   ELSE 1
                                 END,
                                 b.updated_at DESC, b.outsourcing_id DESC
                        LIMIT 200
                        """)
                .param("keyword", normalizedKeyword)
                .param("keywordPattern", normalizedKeyword == null ? null : "%" + normalizedKeyword + "%")
                .param("status", normalizedStatus)
                .query(this::mapProductionOutsourcingBatch)
                .list();
    }

    public ProductionOutsourcingBatchResponse getProductionOutsourcing(
            String batchNo, BootstrapIdentity identity) {
        accessControlService.requireCheckRecordRead(identity);
        String normalizedBatchNo = normalizeCodeValue(batchNo, "batch_no");
        try {
            return outsourcingQuery(" WHERE b.batch_no = :batchNo")
                    .param("batchNo", normalizedBatchNo)
                    .query(this::mapProductionOutsourcingBatch)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "outsourcing batch not found", ex);
        }
    }

    @Transactional
    public ProductionCostRecordResponse createProductionCostRecord(
            ProductionCostRecordRequest request, BootstrapIdentity identity) {
        requireProductionCostWrite(identity);
        CostRecordInput input = normalizeProductionCostRecord(request);
        if ("CONFIRMED".equals(input.status())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "cost record must be confirmed through the confirmation endpoint");
        }
        try {
            jdbcClient.sql("""
                        INSERT INTO production_cost_record
                            (cost_no, order_id, node_instance_id, cost_type, amount, status,
                             department_name, supplier_name, description)
                        VALUES
                            (:costNo, :orderId, :nodeInstanceId, :costType, :amount, :status,
                             :departmentName, :supplierName, :description)
                            """)
                    .param("costNo", input.costNo())
                    .param("orderId", input.orderId())
                    .param("nodeInstanceId", input.nodeInstanceId())
                    .param("costType", input.costType())
                    .param("amount", input.amount())
                    .param("status", input.status())
                    .param("departmentName", input.departmentName())
                    .param("supplierName", input.supplierName())
                    .param("description", input.description())
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "cost_no already exists", ex);
        }
        return loadProductionCostRecord(lastInsertId());
    }

    public ProductionRewardPenaltySummaryResponse getProductionRewardPenaltySummary(
            String recordNoPrefix, BootstrapIdentity identity) {
        accessControlService.requireCheckRecordRead(identity);
        String normalizedPrefix = blankToNull(recordNoPrefix);
        String prefixClause = normalizedPrefix == null ? "" : " WHERE r.record_no LIKE :recordNoPattern";

        JdbcClient.StatementSpec spec = jdbcClient.sql("""
                        SELECT
                            COUNT(*) AS total_record_count,
                            COALESCE(SUM(CASE WHEN r.record_type = 'REWARD' THEN 1 ELSE 0 END), 0)
                                AS reward_count,
                            COALESCE(SUM(CASE WHEN r.record_type = 'PENALTY' THEN 1 ELSE 0 END), 0)
                                AS penalty_count,
                            COALESCE(SUM(CASE WHEN r.status = 'PENDING' THEN 1 ELSE 0 END), 0)
                                AS pending_count,
                            COALESCE(SUM(CASE WHEN r.status = 'APPROVED' THEN 1 ELSE 0 END), 0)
                                AS approved_count,
                            COALESCE(SUM(CASE WHEN r.status = 'REJECTED' THEN 1 ELSE 0 END), 0)
                                AS rejected_count,
                            COALESCE(SUM(CASE WHEN r.status = 'EFFECTIVE' THEN 1 ELSE 0 END), 0)
                                AS effective_count,
                            COUNT(DISTINCT r.order_id) AS related_order_count,
                            COUNT(DISTINCT r.node_instance_id) AS related_process_count,
                            COUNT(DISTINCT r.employee_user_id) AS related_employee_count,
                            COALESCE(SUM(CASE
                                WHEN r.created_at >= DATE_FORMAT(CURRENT_DATE, '%Y-%m-01')
                                     AND r.created_at < DATE_ADD(DATE_FORMAT(CURRENT_DATE, '%Y-%m-01'), INTERVAL 1 MONTH)
                                THEN r.amount ELSE 0 END), 0) AS monthly_amount
                        FROM production_reward_penalty_record r
                        """ + prefixClause);
        if (normalizedPrefix != null) {
            spec = spec.param("recordNoPattern", normalizedPrefix + "%");
        }
        RewardPenaltySummaryRow summary = spec.query((rs, rowNum) -> new RewardPenaltySummaryRow(
                        rs.getLong("total_record_count"),
                        rs.getLong("reward_count"),
                        rs.getLong("penalty_count"),
                        rs.getLong("pending_count"),
                        rs.getLong("approved_count"),
                        rs.getLong("rejected_count"),
                        rs.getLong("effective_count"),
                        rs.getLong("related_order_count"),
                        rs.getLong("related_process_count"),
                        rs.getLong("related_employee_count"),
                        roundedDecimal(rs.getBigDecimal("monthly_amount"), 2)))
                .single();

        return new ProductionRewardPenaltySummaryResponse(
                normalizedPrefix,
                summary.totalRecordCount(),
                summary.rewardCount(),
                summary.penaltyCount(),
                summary.pendingCount(),
                summary.approvedCount(),
                summary.rejectedCount(),
                summary.effectiveCount(),
                summary.relatedOrderCount(),
                summary.relatedProcessCount(),
                summary.relatedEmployeeCount(),
                summary.monthlyAmount(),
                LocalDateTime.now());
    }

    @Transactional
    public ProductionRewardPenaltyRecordResponse createProductionRewardPenaltyRecord(
            ProductionRewardPenaltyRecordRequest request, BootstrapIdentity identity) {
        requireProductionRewardPenaltyWrite(identity);
        RewardPenaltyRecordInput input = normalizeProductionRewardPenaltyRecord(request);
        try {
            jdbcClient.sql("""
                            INSERT INTO production_reward_penalty_record
                                (record_no, record_type, reason_category, amount, status,
                                 order_id, node_instance_id, employee_user_id, approver_user_id,
                                 department_name, description, approved_at, effective_at)
                            VALUES
                                (:recordNo, :recordType, :reasonCategory, :amount, :status,
                                 :orderId, :nodeInstanceId, :employeeUserId, :approverUserId,
                                 :departmentName, :description, :approvedAt, :effectiveAt)
                            """)
                    .param("recordNo", input.recordNo())
                    .param("recordType", input.recordType())
                    .param("reasonCategory", input.reasonCategory())
                    .param("amount", input.amount())
                    .param("status", input.status())
                    .param("orderId", input.orderId())
                    .param("nodeInstanceId", input.nodeInstanceId())
                    .param("employeeUserId", input.employeeUserId())
                    .param("approverUserId", isApprovedRewardPenaltyStatus(input.status()) ? identity.userId() : null)
                    .param("departmentName", input.departmentName())
                    .param("description", input.description())
                    .param("approvedAt", isApprovedRewardPenaltyStatus(input.status()) ? LocalDateTime.now() : null)
                    .param("effectiveAt", "EFFECTIVE".equals(input.status()) ? LocalDateTime.now() : null)
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "record_no already exists", ex);
        }
        return loadProductionRewardPenaltyRecord(lastInsertId());
    }

    @Transactional
    public ProductionRewardPenaltyRecordResponse updateProductionRewardPenaltyRecordStatus(
            String recordNo, ProductionRewardPenaltyStatusRequest request, BootstrapIdentity identity) {
        requireProductionRewardPenaltyWrite(identity);
        String normalizedRecordNo = normalizeRewardPenaltyRecordNo(recordNo);
        String status = normalizeProductionRewardPenaltyStatus(request.status());
        String description = normalizeOptionalDescription(request.description());
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbcClient.sql("""
                        UPDATE production_reward_penalty_record
                        SET status = :status,
                            description = COALESCE(:description, description),
                            approver_user_id = :approverUserId,
                            approved_at = :approvedAt,
                            effective_at = :effectiveAt
                        WHERE record_no = :recordNo
                        """)
                .param("status", status)
                .param("description", description)
                .param("approverUserId", identity.userId())
                .param("approvedAt", isApprovedRewardPenaltyStatus(status) ? now : null)
                .param("effectiveAt", "EFFECTIVE".equals(status) ? now : null)
                .param("recordNo", normalizedRecordNo)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "reward penalty record not found");
        }
        return loadProductionRewardPenaltyRecord(normalizedRecordNo);
    }

    @Transactional
    public ReworkRecordResponse closeRework(long reworkId, ReworkCloseRequest request, BootstrapIdentity identity) {
        ReworkRow rework = lockRework(reworkId);
        NodeRow targetNode = lockNode(rework.targetNodeInstanceId());
        requireWorkerAssignment(targetNode, identity);
        if ("DONE".equals(rework.status())) {
            return loadRework(reworkId);
        }
        boolean hasReworkOutPass = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM check_record
                        WHERE node_instance_id = :targetNodeInstanceId
                          AND check_type = 'OUT'
                          AND result = 'PASS'
                          AND check_id > :sourceCheckId
                        """)
                .param("targetNodeInstanceId", rework.targetNodeInstanceId())
                .param("sourceCheckId", rework.sourceCheckId())
                .query(Long.class)
                .single() > 0;
        if (!hasReworkOutPass) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "rework target OUT/PASS check is required before closing rework");
        }
        // 客户规则：内返由部门组长登记，责任由质检确认。关闭返工时要填责任方，因此这里要求责任确认权限码。
        accessControlService.requirePermission(
                identity, "rework:confirm-responsibility", "closing rework requires rework:confirm-responsibility");
        String reasonCategory = normalizeDictionaryValue(
                request.reasonCategory(), REWORK_REASON_CATEGORY_TYPE, "unsupported rework reason category");
        String responsibilityType = normalizeDictionaryValue(
                request.responsibilityType(), REWORK_RESPONSIBILITY_TYPE, "unsupported rework responsibility type");
        jdbcClient.sql("""
                        UPDATE rework_record
                        SET reason_category = :reasonCategory,
                            responsibility_type = :responsibilityType,
                            close_note = :closeNote,
                            closed_by_user_id = :closedByUserId,
                            closed_at = CURRENT_TIMESTAMP(3),
                            status = 'DONE'
                        WHERE rework_id = :reworkId
                        """)
                .param("reasonCategory", reasonCategory)
                .param("responsibilityType", responsibilityType)
                .param("closeNote", blankToNull(request.closeNote()))
                .param("closedByUserId", identity.userId())
                .param("reworkId", reworkId)
                .update();
        ReworkRecordResponse closed = loadRework(reworkId);
        ReworkNotificationRow notification = loadReworkNotification(reworkId);
        emitReworkNotification(
                notification,
                "REWORK_CLOSED",
                "CS",
                notification.csUserId(),
                "返工已关闭");
        return closed;
    }

    @Transactional
    public FinalInspectionReportResponse createFinalInspectionReport(
            FinalInspectionReportRequest request, BootstrapIdentity identity) {
        requireAdminOrWorkerPermission(
                identity,
                "final-inspection:manage",
                "final inspection report requires final-inspection:manage");
        if (request.orderId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "order_id is required");
        }
        NodeRow finalNode = lockFinalNode(request.orderId());
        requireWorkerAssignment(finalNode, identity);
        FinalCheckRow finalCheck = findLatestFinalOutPass(finalNode.nodeInstanceId());
        FinalInspectionReportResponse existing = findFinalInspectionReport(request.orderId());
        if (existing != null) {
            return existing;
        }
        String summary = request.summary() == null || request.summary().isBlank()
                ? "终检通过"
                : request.summary().trim();
        Long pdfFileId = normalizePdfFileId(request.pdfFileId());
        validateFinalInspectionPdfFile(request.orderId(), pdfFileId);
        List<Long> attachmentFileIds = normalizeAttachmentFileIds(request.attachmentFileIds());
        validateFinalInspectionAttachmentFiles(request.orderId(), attachmentFileIds);
        String reportNo = "FIR-" + request.orderId() + "-" + finalCheck.checkId();
        jdbcClient.sql("""
                        INSERT INTO final_inspection_report
                            (order_id, report_no, final_node_instance_id, final_check_id,
                             conclusion, summary, pdf_file_id, inspector_user_id, status, signature_status)
                        VALUES
                            (:orderId, :reportNo, :finalNodeInstanceId, :finalCheckId,
                             'PASS', :summary, :pdfFileId, :inspectorUserId, 'ISSUED', 'PENDING')
                        """)
                .param("orderId", request.orderId())
                .param("reportNo", reportNo)
                .param("finalNodeInstanceId", finalNode.nodeInstanceId())
                .param("finalCheckId", finalCheck.checkId())
                .param("summary", summary)
                .param("pdfFileId", pdfFileId)
                .param("inspectorUserId", identity.userId())
                .update();
        long reportId = lastInsertId();
        insertFinalInspectionReportFiles(reportId, attachmentFileIds);
        return loadFinalInspectionReportById(reportId);
    }

    public FinalInspectionReportResponse getFinalInspectionReport(
            long orderId, BootstrapIdentity identity, boolean allowAbsent) {
        accessControlService.requireCheckRecordRead(identity);
        if (identity.role() == com.yuri.aiorder.common.UserRole.WORKER) {
            NodeRow finalNode = loadFinalNode(orderId);
            requireWorkerAssignment(finalNode, identity);
        }
        FinalInspectionReportResponse report = findFinalInspectionReport(orderId);
        if (report == null && !allowAbsent) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "final inspection report not found");
        }
        return report;
    }

    @Transactional
    public WorkLogResponse startWorkLog(WorkLogStartRequest request, BootstrapIdentity identity) {
        if (request.nodeInstanceId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "node_instance_id is required");
        }
        NodeRow node = lockNode(request.nodeInstanceId());
        requireWorkerAssignment(node, identity);
        if (!"IN_PROGRESS".equals(node.nodeStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "work log can start only for in-progress node");
        }
        Long existing = jdbcClient.sql("""
                        SELECT work_log_id
                        FROM work_log
                        WHERE node_instance_id = :nodeInstanceId
                          AND worker_user_id = :workerUserId
                          AND status IN ('IN_PROGRESS', 'PAUSED')
                        ORDER BY work_log_id DESC
                        LIMIT 1
                        """)
                .param("nodeInstanceId", node.nodeInstanceId())
                .param("workerUserId", identity.userId())
                .query(Long.class)
                .optional()
                .orElse(null);
        if (existing != null) {
            return loadWorkLog(existing, true);
        }
        jdbcClient.sql("""
                        INSERT INTO work_log
                            (order_id, node_instance_id, worker_user_id, started_at, status)
                        VALUES
                            (:orderId, :nodeInstanceId, :workerUserId, CURRENT_TIMESTAMP(3), 'IN_PROGRESS')
                        """)
                .param("orderId", node.orderId())
                .param("nodeInstanceId", node.nodeInstanceId())
                .param("workerUserId", identity.userId())
                .update();
        return loadWorkLog(lastInsertId(), true);
    }

    @Transactional
    public WorkLogResponse pauseWorkLog(long workLogId, BootstrapIdentity identity) {
        WorkLogRow workLog = lockWorkLog(workLogId);
        requireWorkLogOwner(workLog, identity);
        if ("IN_PROGRESS".equals(workLog.status())) {
            boolean hasOpenPause = hasOpenPause(workLogId);
            if (!hasOpenPause) {
                jdbcClient.sql("""
                                INSERT INTO work_log_pause_segment (work_log_id, paused_at)
                                VALUES (:workLogId, CURRENT_TIMESTAMP(3))
                                """)
                        .param("workLogId", workLogId)
                        .update();
            }
            jdbcClient.sql("UPDATE work_log SET status = 'PAUSED' WHERE work_log_id = :workLogId")
                    .param("workLogId", workLogId)
                    .update();
        }
        return loadWorkLog(workLogId, true);
    }

    @Transactional
    public WorkLogResponse resumeWorkLog(long workLogId, BootstrapIdentity identity) {
        WorkLogRow workLog = lockWorkLog(workLogId);
        requireWorkLogOwner(workLog, identity);
        if ("PAUSED".equals(workLog.status())) {
            closeOpenPause(workLogId);
            jdbcClient.sql("UPDATE work_log SET status = 'IN_PROGRESS' WHERE work_log_id = :workLogId")
                    .param("workLogId", workLogId)
                    .update();
        }
        return loadWorkLog(workLogId, true);
    }

    @Transactional
    public WorkLogResponse finishWorkLog(long workLogId, BootstrapIdentity identity) {
        WorkLogRow workLog = lockWorkLog(workLogId);
        requireWorkLogOwner(workLog, identity);
        if ("COMPLETED".equals(workLog.status())) {
            return loadWorkLog(workLogId, true);
        }
        if ("PAUSED".equals(workLog.status())) {
            closeOpenPause(workLogId);
        }
        jdbcClient.sql("""
                        UPDATE work_log
                        SET finished_at = CURRENT_TIMESTAMP(3),
                            effective_duration_seconds = GREATEST(
                                TIMESTAMPDIFF(SECOND, started_at, CURRENT_TIMESTAMP(3)) - pause_duration_seconds,
                                0
                            ),
                            status = 'COMPLETED'
                        WHERE work_log_id = :workLogId
                        """)
                .param("workLogId", workLogId)
                .update();
        return loadWorkLog(workLogId, true);
    }

    public PerformanceStatsResponse getPerformance(
            Long requestedUserId, LocalDate startDate, LocalDate endDate, BootstrapIdentity identity) {
        Long targetUserId = accessControlService.resolvePerformanceTargetUserId(identity, requestedUserId);
        PerformancePeriodFilter period = performancePeriodFilter(startDate, endDate);
        long completedCount = countLong("""
                        SELECT COUNT(*)
                        FROM work_log w
                        WHERE worker_user_id = :userId
                          AND status = 'COMPLETED'
                        """ + periodSql(period, "w.finished_at"), targetUserId, period);
        long effectiveSeconds = countLong("""
                        SELECT COALESCE(SUM(effective_duration_seconds), 0)
                        FROM work_log w
                        WHERE worker_user_id = :userId
                          AND status = 'COMPLETED'
                        """ + periodSql(period, "w.finished_at"), targetUserId, period);
        long reworkCount = countLong("""
                        SELECT COUNT(*)
                        FROM rework_record r
                        JOIN order_process_node n ON n.node_instance_id = r.target_node_instance_id
                        WHERE n.assigned_user_id = :userId
                        """ + periodSql(period, "r.created_at"), targetUserId, period);
        long responsibleReworkCount = countLong("""
                        SELECT COUNT(*)
                        FROM rework_record r
                        JOIN order_process_node n ON n.node_instance_id = r.target_node_instance_id
                        WHERE n.assigned_user_id = :userId
                          AND r.responsibility_type = 'WORKER'
                        """ + periodSql(period, "r.created_at"), targetUserId, period);
        long nonWorkerResponsibilityReworkCount = countLong("""
                        SELECT COUNT(*)
                        FROM rework_record r
                        JOIN order_process_node n ON n.node_instance_id = r.target_node_instance_id
                        WHERE n.assigned_user_id = :userId
                          AND r.responsibility_type IN ('DOCTOR', 'CS', 'SYSTEM')
                        """ + periodSql(period, "r.created_at"), targetUserId, period);
        long unclassifiedReworkCount = countLong("""
                        SELECT COUNT(*)
                        FROM rework_record r
                        JOIN order_process_node n ON n.node_instance_id = r.target_node_instance_id
                        WHERE n.assigned_user_id = :userId
                          AND r.responsibility_type IS NULL
                        """ + periodSql(period, "r.created_at"), targetUserId, period);
        long outCheckTotal = countLong("""
                        SELECT COUNT(*)
                        FROM check_record c
                        JOIN order_process_node n ON n.node_instance_id = c.node_instance_id
                        WHERE n.assigned_user_id = :userId
                          AND c.check_type = 'OUT'
                        """ + periodSql(period, "c.created_at"), targetUserId, period);
        long outCheckPass = countLong("""
                        SELECT COUNT(*)
                        FROM check_record c
                        JOIN order_process_node n ON n.node_instance_id = c.node_instance_id
                        WHERE n.assigned_user_id = :userId
                          AND c.check_type = 'OUT'
                          AND c.result = 'PASS'
                        """ + periodSql(period, "c.created_at"), targetUserId, period);
        boolean formalStandardTimeEnabled = standardTimeProperties.formalEnabled();
        long onTimeCount = formalStandardTimeEnabled ? countLong("""
                        SELECT COUNT(*)
                        FROM work_log w
                        JOIN order_process_node n ON n.node_instance_id = w.node_instance_id
                        WHERE w.worker_user_id = :userId
                          AND w.status = 'COMPLETED'
                          AND n.standard_duration IS NOT NULL
                          AND w.effective_duration_seconds <= n.standard_duration * 60
                        """ + periodSql(period, "w.finished_at"), targetUserId, period) : 0;
        long standardMinutes = formalStandardTimeEnabled ? countLong("""
                        SELECT COALESCE(SUM(n.standard_duration), 0)
                        FROM work_log w
                        JOIN order_process_node n ON n.node_instance_id = w.node_instance_id
                        WHERE w.worker_user_id = :userId
                          AND w.status = 'COMPLETED'
                          AND n.standard_duration IS NOT NULL
                        """ + periodSql(period, "w.finished_at"), targetUserId, period) : 0;
        long standardCoveredCount = formalStandardTimeEnabled ? countLong("""
                        SELECT COUNT(*)
                        FROM work_log w
                        JOIN order_process_node n ON n.node_instance_id = w.node_instance_id
                        WHERE w.worker_user_id = :userId
                          AND w.status = 'COMPLETED'
                          AND n.standard_duration IS NOT NULL
                        """ + periodSql(period, "w.finished_at"), targetUserId, period) : 0;
        long standardMissingCount = Math.max(completedCount - standardCoveredCount, 0);
        int onTimeRate = percent(onTimeCount, completedCount);
        int passRate = percent(outCheckPass, outCheckTotal);
        int durationEfficiency = effectiveSeconds == 0
                ? 0
                : Math.toIntExact(Math.round((standardMinutes * 60.0 * 100.0) / effectiveSeconds));
        return new PerformanceStatsResponse(
                targetUserId,
                formalStandardTimeEnabled ? PERFORMANCE_FORMULA_VERSION : "STANDARD_TIME_PENDING",
                completedCount,
                effectiveSeconds / 60,
                formalStandardTimeEnabled ? standardMinutes : null,
                formalStandardTimeEnabled ? standardCoveredCount : null,
                formalStandardTimeEnabled ? standardMissingCount : null,
                formalStandardTimeEnabled ? percent(standardCoveredCount, completedCount) : null,
                reworkCount,
                responsibleReworkCount,
                nonWorkerResponsibilityReworkCount,
                unclassifiedReworkCount,
                formalStandardTimeEnabled ? onTimeRate : null,
                passRate,
                formalStandardTimeEnabled ? durationEfficiency : null,
                formalStandardTimeEnabled
                        ? performanceScore(
                                durationEfficiency,
                                passRate,
                                onTimeRate,
                                responsibleReworkCount,
                                unclassifiedReworkCount)
                        : null);
    }

    public List<PerformanceDetailResponse> getPerformanceDetails(
            Long requestedUserId, LocalDate startDate, LocalDate endDate, BootstrapIdentity identity) {
        Long targetUserId = accessControlService.resolvePerformanceTargetUserId(identity, requestedUserId);
        PerformancePeriodFilter period = performancePeriodFilter(startDate, endDate);
        var statement = jdbcClient.sql("""
                        SELECT
                            w.work_log_id,
                            w.order_id,
                            o.order_no,
                            w.node_instance_id,
                            n.process_name,
                            w.worker_user_id,
                            w.status,
                            w.effective_duration_seconds,
                            n.standard_duration,
                            w.started_at,
                            w.finished_at
                        FROM work_log w
                        JOIN orders o ON o.order_id = w.order_id
                        JOIN order_process_node n ON n.node_instance_id = w.node_instance_id
                        WHERE w.worker_user_id = :userId
                          AND w.status = 'COMPLETED'
                        """ + periodSql(period, "w.finished_at") + """
                        ORDER BY w.finished_at DESC, w.work_log_id DESC
                        LIMIT 100
                        """)
                .param("userId", targetUserId);
        statement = bindPeriod(statement, period);
        return statement.query((rs, rowNum) -> {
                    Integer effectiveSeconds = rs.getObject("effective_duration_seconds", Integer.class);
                    Integer standardMinutes = standardTimeProperties.formalEnabled()
                            ? rs.getObject("standard_duration", Integer.class)
                            : null;
                    Boolean onTime = standardMinutes == null || effectiveSeconds == null
                            ? null
                            : effectiveSeconds <= standardMinutes * 60;
                    return new PerformanceDetailResponse(
                            rs.getLong("work_log_id"),
                            rs.getLong("order_id"),
                            rs.getString("order_no"),
                            rs.getLong("node_instance_id"),
                            rs.getString("process_name"),
                            rs.getLong("worker_user_id"),
                            rs.getString("status"),
                            effectiveSeconds == null ? null : effectiveSeconds / 60,
                            standardMinutes,
                            onTime,
                            rs.getObject("started_at", LocalDateTime.class),
                            rs.getObject("finished_at", LocalDateTime.class));
                })
                .list();
    }

    /**
     * 解析返工目标节点所属部门的组长。
     *
     * <p>部门归属取自被退回节点执行人所在部门（{@code system_user.dept_id}）；组长取该部门内持有
     * {@code PROD_TEAM_LEAD} 角色且在岗的用户，同部门多名组长时取 user_id 最小的一个。
     * 真实部门 / 班组清单属客户未提供资料，解析不到时两个字段留空，不阻塞返工创建。
     */
    private TeamLeadRoute resolveTeamLeadRoute(NodeRow target) {
        if (target.assignedUserId() == null) {
            return new TeamLeadRoute(null, null);
        }
        Long deptId = jdbcClient.sql("SELECT dept_id FROM system_user WHERE user_id = :userId")
                .param("userId", target.assignedUserId())
                .query(Long.class)
                .optional()
                .orElse(null);
        if (deptId == null) {
            return new TeamLeadRoute(null, null);
        }
        Long teamLeadUserId = jdbcClient.sql("""
                        SELECT u.user_id
                        FROM system_user u
                        JOIN system_user_role ur ON ur.user_id = u.user_id
                        JOIN system_role r ON r.role_id = ur.role_id
                        WHERE u.dept_id = :deptId
                          AND u.status = 'ACTIVE'
                          AND r.role_code = 'PROD_TEAM_LEAD'
                          AND r.status = 'ACTIVE'
                        ORDER BY u.user_id ASC
                        LIMIT 1
                        """)
                .param("deptId", deptId)
                .query(Long.class)
                .optional()
                .orElse(null);
        return new TeamLeadRoute(deptId, teamLeadUserId);
    }

    private record TeamLeadRoute(Long deptId, Long teamLeadUserId) {
    }

    private Long createRework(NodeRow node, long checkId, CheckRecordRequest request) {
        if (request.reworkToNodeId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rework_to_node_id is required when out-check fails");
        }
        NodeRow target = lockNode(request.reworkToNodeId());
        if (target.orderId() != node.orderId()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rework target must belong to same order");
        }
        List<Long> impactedNodeIds = findImpactedResettableDownstreamNodeIds(target);
        // 客户规则：终检不合格退回负责部门的组长。这里把退回目标固化成事实，供组长在返工列表里认领。
        TeamLeadRoute route = resolveTeamLeadRoute(target);
        jdbcClient.sql("""
                        INSERT INTO rework_record
                            (order_id, source_check_id, from_node_instance_id, target_node_instance_id,
                             impacted_node_count, impacted_node_instance_ids, reason_detail, status,
                             routed_dept_id, routed_to_user_id)
                        VALUES
                            (:orderId, :sourceCheckId, :fromNodeInstanceId, :targetNodeInstanceId,
                             :impactedNodeCount, CAST(:impactedNodeInstanceIds AS JSON), :reasonDetail, 'PENDING',
                             :routedDeptId, :routedToUserId)
                        """)
                .param("orderId", node.orderId())
                .param("sourceCheckId", checkId)
                .param("fromNodeInstanceId", node.nodeInstanceId())
                .param("targetNodeInstanceId", target.nodeInstanceId())
                .param("impactedNodeCount", impactedNodeIds.size())
                .param("impactedNodeInstanceIds", serializeImpactedNodeInstanceIds(impactedNodeIds))
                .param("reasonDetail", request.remark())
                .param("routedDeptId", route.deptId())
                .param("routedToUserId", route.teamLeadUserId())
                .update();
        long reworkId = lastInsertId();
        resetImpactedDownstreamNodes(target);
        jdbcClient.sql("""
                        UPDATE order_process_node
                        SET node_status = 'READY',
                            started_at = NULL,
                            completed_at = NULL
                        WHERE node_instance_id = :nodeInstanceId
                        """)
                .param("nodeInstanceId", target.nodeInstanceId())
                .update();
        jdbcClient.sql("""
                        UPDATE order_process_instance
                        SET instance_status = 'ACTIVE'
                        WHERE instance_id = :instanceId
                        """)
                .param("instanceId", target.instanceId())
                .update();
        emitReworkNotification(
                loadReworkNotification(reworkId),
                "REWORK_CREATED",
                "WORKER",
                target.assignedUserId(),
                "返工待处理");
        return reworkId;
    }

    private void resetImpactedDownstreamNodes(NodeRow target) {
        jdbcClient.sql("""
                        WITH RECURSIVE impacted_nodes(node_instance_id) AS (
                            SELECT edge.to_node_instance_id
                            FROM order_process_edge edge
                            WHERE edge.instance_id = :instanceId
                              AND edge.from_node_instance_id = :targetNodeInstanceId
                            UNION DISTINCT
                            SELECT edge.to_node_instance_id
                            FROM order_process_edge edge
                            JOIN impacted_nodes impacted
                              ON impacted.node_instance_id = edge.from_node_instance_id
                            WHERE edge.instance_id = :instanceId
                        )
                        UPDATE order_process_node node
                        JOIN impacted_nodes impacted
                          ON impacted.node_instance_id = node.node_instance_id
                        SET node.node_status = 'PENDING',
                            node.started_at = NULL,
                            node.completed_at = NULL
                        WHERE node.instance_id = :instanceId
                          AND node.node_status IN ('READY', 'COMPLETED')
                        """)
                .param("instanceId", target.instanceId())
                .param("targetNodeInstanceId", target.nodeInstanceId())
                .update();
    }

    private List<Long> findImpactedResettableDownstreamNodeIds(NodeRow target) {
        return jdbcClient.sql("""
                        WITH RECURSIVE impacted_nodes(node_instance_id) AS (
                            SELECT edge.to_node_instance_id
                            FROM order_process_edge edge
                            WHERE edge.instance_id = :instanceId
                              AND edge.from_node_instance_id = :targetNodeInstanceId
                            UNION DISTINCT
                            SELECT edge.to_node_instance_id
                            FROM order_process_edge edge
                            JOIN impacted_nodes impacted
                              ON impacted.node_instance_id = edge.from_node_instance_id
                            WHERE edge.instance_id = :instanceId
                        )
                        SELECT node.node_instance_id
                        FROM order_process_node node
                        JOIN impacted_nodes impacted
                          ON impacted.node_instance_id = node.node_instance_id
                        WHERE node.instance_id = :instanceId
                          AND node.node_status IN ('READY', 'COMPLETED')
                        ORDER BY node.step_order, node.node_instance_id
                        """)
                .param("instanceId", target.instanceId())
                .param("targetNodeInstanceId", target.nodeInstanceId())
                .query(Long.class)
                .list();
    }

    private void closeOpenPause(long workLogId) {
        jdbcClient.sql("""
                        UPDATE work_log w
                        JOIN work_log_pause_segment p ON p.work_log_id = w.work_log_id
                        SET w.pause_duration_seconds = w.pause_duration_seconds
                                + GREATEST(TIMESTAMPDIFF(SECOND, p.paused_at, CURRENT_TIMESTAMP(3)), 0),
                            p.resumed_at = CURRENT_TIMESTAMP(3)
                        WHERE w.work_log_id = :workLogId
                          AND p.resumed_at IS NULL
                        """)
                .param("workLogId", workLogId)
                .update();
    }

    private NodeRow lockFinalNode(long orderId) {
        try {
            return jdbcClient.sql("""
                            SELECT
                                n.node_instance_id,
                                n.instance_id,
                                i.order_id,
                                n.assigned_user_id,
                                n.node_status
                            FROM order_process_node n
                            JOIN order_process_instance i ON i.instance_id = n.instance_id
                            WHERE i.order_id = :orderId
                              AND n.step_order = (
                                  SELECT MAX(last_node.step_order)
                                  FROM order_process_node last_node
                                  JOIN order_process_instance last_instance
                                    ON last_instance.instance_id = last_node.instance_id
                                  WHERE last_instance.order_id = :orderId
                              )
                            ORDER BY n.node_instance_id DESC
                            LIMIT 1
                            FOR UPDATE
                            """)
                    .param("orderId", orderId)
                    .query((rs, rowNum) -> new NodeRow(
                            rs.getLong("node_instance_id"),
                            rs.getLong("instance_id"),
                            rs.getLong("order_id"),
                            rs.getObject("assigned_user_id", Long.class),
                            rs.getString("node_status")))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "final process node not found", ex);
        }
    }

    private NodeRow loadFinalNode(long orderId) {
        try {
            return jdbcClient.sql("""
                            SELECT
                                n.node_instance_id,
                                n.instance_id,
                                i.order_id,
                                n.assigned_user_id,
                                n.node_status
                            FROM order_process_node n
                            JOIN order_process_instance i ON i.instance_id = n.instance_id
                            WHERE i.order_id = :orderId
                              AND n.step_order = (
                                  SELECT MAX(last_node.step_order)
                                  FROM order_process_node last_node
                                  JOIN order_process_instance last_instance
                                    ON last_instance.instance_id = last_node.instance_id
                                  WHERE last_instance.order_id = :orderId
                              )
                            ORDER BY n.node_instance_id DESC
                            LIMIT 1
                            """)
                    .param("orderId", orderId)
                    .query((rs, rowNum) -> new NodeRow(
                            rs.getLong("node_instance_id"),
                            rs.getLong("instance_id"),
                            rs.getLong("order_id"),
                            rs.getObject("assigned_user_id", Long.class),
                            rs.getString("node_status")))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "final process node not found", ex);
        }
    }

    private FinalCheckRow findLatestFinalOutPass(long finalNodeInstanceId) {
        return jdbcClient.sql("""
                        SELECT check_id
                        FROM check_record
                        WHERE node_instance_id = :nodeInstanceId
                          AND check_type = 'OUT'
                          AND result = 'PASS'
                        ORDER BY check_id DESC
                        LIMIT 1
                        """)
                .param("nodeInstanceId", finalNodeInstanceId)
                .query((rs, rowNum) -> new FinalCheckRow(rs.getLong("check_id")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "final OUT/PASS check is required before final inspection report"));
    }

    private FinalInspectionReportResponse findFinalInspectionReport(long orderId) {
        return jdbcClient.sql("""
                        SELECT report_id, order_id, report_no, final_node_instance_id, final_check_id,
                               conclusion, summary, pdf_file_id, inspector_user_id, status,
                               signature_status, signed_by_user_id, signed_at, created_at
                        FROM final_inspection_report
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new FinalInspectionReportResponse(
                        rs.getLong("report_id"),
                        rs.getLong("order_id"),
                        rs.getString("report_no"),
                        rs.getLong("final_node_instance_id"),
                        rs.getLong("final_check_id"),
                        rs.getString("conclusion"),
                        rs.getString("summary"),
                        rs.getObject("pdf_file_id", Long.class),
                        rs.getObject("inspector_user_id", Long.class),
                        rs.getString("status"),
                        rs.getString("signature_status"),
                        rs.getObject("signed_by_user_id", Long.class),
                        rs.getObject("signed_at", LocalDateTime.class),
                        rs.getObject("created_at", LocalDateTime.class),
                        loadFinalInspectionAttachmentFileIds(rs.getLong("report_id"))))
                .optional()
                .orElse(null);
    }

    private FinalInspectionReportResponse loadFinalInspectionReportById(long reportId) {
        return jdbcClient.sql("""
                        SELECT report_id, order_id, report_no, final_node_instance_id, final_check_id,
                               conclusion, summary, pdf_file_id, inspector_user_id, status,
                               signature_status, signed_by_user_id, signed_at, created_at
                        FROM final_inspection_report
                        WHERE report_id = :reportId
                        """)
                .param("reportId", reportId)
                .query((rs, rowNum) -> new FinalInspectionReportResponse(
                        rs.getLong("report_id"),
                        rs.getLong("order_id"),
                        rs.getString("report_no"),
                        rs.getLong("final_node_instance_id"),
                        rs.getLong("final_check_id"),
                        rs.getString("conclusion"),
                        rs.getString("summary"),
                        rs.getObject("pdf_file_id", Long.class),
                        rs.getObject("inspector_user_id", Long.class),
                        rs.getString("status"),
                        rs.getString("signature_status"),
                        rs.getObject("signed_by_user_id", Long.class),
                        rs.getObject("signed_at", LocalDateTime.class),
                        rs.getObject("created_at", LocalDateTime.class),
                        loadFinalInspectionAttachmentFileIds(rs.getLong("report_id"))))
                .single();
    }

    private Long normalizePdfFileId(Long pdfFileId) {
        return pdfFileId == null || pdfFileId <= 0 ? null : pdfFileId;
    }

    private void validateFinalInspectionPdfFile(long orderId, Long pdfFileId) {
        if (pdfFileId == null) {
            return;
        }
        long validCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM file_resource
                        WHERE order_id = :orderId
                          AND status = 'ACTIVE'
                          AND upload_status = 'COMPLETED'
                          AND visibility = 'INTERNAL'
                          AND content_type = 'application/pdf'
                          AND file_id = :pdfFileId
                        """)
                .param("orderId", orderId)
                .param("pdfFileId", pdfFileId)
                .query(Long.class)
                .single();
        if (validCount != 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "final inspection pdf must be a completed internal PDF file for this order");
        }
    }

    private List<Long> normalizeAttachmentFileIds(List<Long> attachmentFileIds) {
        if (attachmentFileIds == null || attachmentFileIds.isEmpty()) {
            return List.of();
        }
        return attachmentFileIds.stream()
                .filter(fileId -> fileId != null && fileId > 0)
                .distinct()
                .toList();
    }

    private void validateFinalInspectionAttachmentFiles(long orderId, List<Long> attachmentFileIds) {
        if (attachmentFileIds.isEmpty()) {
            return;
        }
        long validCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM file_resource
                        WHERE order_id = :orderId
                          AND status = 'ACTIVE'
                          AND upload_status = 'COMPLETED'
                          AND visibility = 'INTERNAL'
                          AND file_id IN (:fileIds)
                        """)
                .param("orderId", orderId)
                .param("fileIds", attachmentFileIds)
                .query(Long.class)
                .single();
        if (validCount != attachmentFileIds.size()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "final inspection attachments must be completed internal files for this order");
        }
    }

    private void insertFinalInspectionReportFiles(long reportId, List<Long> attachmentFileIds) {
        for (int index = 0; index < attachmentFileIds.size(); index++) {
            jdbcClient.sql("""
                            INSERT INTO final_inspection_report_file (report_id, file_id, sort_order)
                            VALUES (:reportId, :fileId, :sortOrder)
                            """)
                    .param("reportId", reportId)
                    .param("fileId", attachmentFileIds.get(index))
                    .param("sortOrder", index + 1)
                    .update();
        }
    }

    private List<Long> loadFinalInspectionAttachmentFileIds(long reportId) {
        return jdbcClient.sql("""
                        SELECT file_id
                        FROM final_inspection_report_file
                        WHERE report_id = :reportId
                        ORDER BY sort_order, file_id
                        """)
                .param("reportId", reportId)
                .query(Long.class)
                .list();
    }

    private ReworkRow lockRework(long reworkId) {
        try {
            return jdbcClient.sql("""
                            SELECT rework_id, source_check_id, target_node_instance_id, status
                            FROM rework_record
                            WHERE rework_id = :reworkId
                            FOR UPDATE
                            """)
                    .param("reworkId", reworkId)
                    .query((rs, rowNum) -> new ReworkRow(
                            rs.getLong("rework_id"),
                            rs.getLong("source_check_id"),
                            rs.getLong("target_node_instance_id"),
                            rs.getString("status")))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "rework record not found", ex);
        }
    }

    private ReworkRecordResponse loadRework(long reworkId) {
        try {
            return jdbcClient.sql("""
                            SELECT
                                r.rework_id,
                                r.order_id,
                                o.order_no,
                                r.source_check_id,
                                r.from_node_instance_id,
                                from_node.process_name AS from_process_name,
                            r.target_node_instance_id,
                            target_node.process_name AS target_process_name,
                            target_node.node_status AS target_node_status,
                            r.impacted_node_count,
                            CAST(r.impacted_node_instance_ids AS CHAR) AS impacted_node_instance_ids,
                            target_node.assigned_user_id,
                                r.reason_category,
                                r.reason_detail,
                                r.responsibility_type,
                                r.routed_dept_id,
                                r.routed_to_user_id,
                                r.close_note,
                                r.closed_by_user_id,
                                r.closed_at,
                                r.status,
                                r.created_at
                            FROM rework_record r
                            JOIN orders o ON o.order_id = r.order_id
                            LEFT JOIN order_process_node from_node
                              ON from_node.node_instance_id = r.from_node_instance_id
                            LEFT JOIN order_process_node target_node
                              ON target_node.node_instance_id = r.target_node_instance_id
                            WHERE r.rework_id = :reworkId
                            """)
                    .param("reworkId", reworkId)
                    .query((rs, rowNum) -> new ReworkRecordResponse(
                            rs.getLong("rework_id"),
                            rs.getLong("order_id"),
                            rs.getString("order_no"),
                            rs.getLong("source_check_id"),
                            rs.getObject("from_node_instance_id", Long.class),
                            rs.getString("from_process_name"),
                            rs.getObject("target_node_instance_id", Long.class),
                            rs.getString("target_process_name"),
                            rs.getString("target_node_status"),
                            rs.getInt("impacted_node_count"),
                            parseImpactedNodeInstanceIds(rs.getString("impacted_node_instance_ids")),
                            rs.getObject("assigned_user_id", Long.class),
                            rs.getString("reason_category"),
                            rs.getString("reason_detail"),
                            rs.getString("responsibility_type"),
                            rs.getObject("routed_dept_id", Long.class),
                            rs.getObject("routed_to_user_id", Long.class),
                            rs.getString("close_note"),
                            rs.getObject("closed_by_user_id", Long.class),
                            rs.getObject("closed_at", LocalDateTime.class),
                            rs.getString("status"),
                            rs.getObject("created_at", LocalDateTime.class)))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "rework record not found", ex);
        }
    }

    private ReworkNotificationRow loadReworkNotification(long reworkId) {
        return jdbcClient.sql("""
                        SELECT
                            r.rework_id,
                            r.order_id,
                            o.order_no,
                            o.cs_user_id,
                            r.target_node_instance_id
                        FROM rework_record r
                        JOIN orders o ON o.order_id = r.order_id
                        WHERE r.rework_id = :reworkId
                        """)
                .param("reworkId", reworkId)
                .query((rs, rowNum) -> new ReworkNotificationRow(
                        rs.getLong("rework_id"),
                        rs.getLong("order_id"),
                        rs.getString("order_no"),
                        rs.getObject("cs_user_id", Long.class),
                        rs.getLong("target_node_instance_id")))
                .single();
    }

    private void emitReworkNotification(
            ReworkNotificationRow rework, String eventType, String audienceRole, Long userId, String message) {
        String payload = reworkPayload(rework, eventType, message);
        jdbcClient.sql("""
                        INSERT INTO notification_event
                            (order_id, event_type, audience_role, payload, delivery_status)
                        VALUES
                            (:orderId, :eventType, :audienceRole, CAST(:payload AS JSON), 'PENDING')
                        """)
                .param("orderId", rework.orderId())
                .param("eventType", eventType)
                .param("audienceRole", audienceRole)
                .param("payload", payload)
                .update();
        long eventId = lastInsertId();
        if (userId == null) {
            return;
        }
        jdbcClient.sql("""
                        INSERT IGNORE INTO user_notification (event_id, user_id)
                        VALUES (:eventId, :userId)
                        """)
                .param("eventId", eventId)
                .param("userId", userId)
                .update();
        notificationPushService.pushToUser(userId, eventId, payload);
    }

    private String reworkPayload(ReworkNotificationRow rework, String eventType, String message) {
        try {
            return objectMapper.writeValueAsString(new ReworkNotificationPayload(
                    eventType,
                    rework.orderId(),
                    rework.orderNo(),
                    message,
                    rework.reworkId(),
                    rework.targetNodeInstanceId()));
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "failed to build rework notification payload", ex);
        }
    }

    private boolean hasOpenPause(long workLogId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM work_log_pause_segment
                        WHERE work_log_id = :workLogId
                          AND resumed_at IS NULL
                        """)
                .param("workLogId", workLogId)
                .query(Long.class)
                .single() > 0;
    }

    private WorkLogResponse loadWorkLog(long workLogId, boolean requireExisting) {
        try {
            return jdbcClient.sql("""
                            SELECT work_log_id, node_instance_id, worker_user_id, status,
                                   pause_duration_seconds, effective_duration_seconds
                            FROM work_log
                            WHERE work_log_id = :workLogId
                            """)
                    .param("workLogId", workLogId)
                    .query((rs, rowNum) -> new WorkLogResponse(
                            rs.getLong("work_log_id"),
                            rs.getLong("node_instance_id"),
                            rs.getLong("worker_user_id"),
                            rs.getString("status"),
                            rs.getInt("pause_duration_seconds"),
                            rs.getObject("effective_duration_seconds", Integer.class)))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            if (requireExisting) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "work log not found", ex);
            }
            return null;
        }
    }

    private NodeRow lockNode(long nodeInstanceId) {
        try {
            return jdbcClient.sql("""
                            SELECT
                                n.node_instance_id,
                                n.instance_id,
                                i.order_id,
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
                            rs.getObject("assigned_user_id", Long.class),
                            rs.getString("node_status")))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "process node not found", ex);
        }
    }

    private WorkLogRow lockWorkLog(long workLogId) {
        try {
            return jdbcClient.sql("""
                            SELECT work_log_id, worker_user_id, status, started_at
                            FROM work_log
                            WHERE work_log_id = :workLogId
                            FOR UPDATE
                            """)
                    .param("workLogId", workLogId)
                    .query((rs, rowNum) -> new WorkLogRow(
                            rs.getLong("work_log_id"),
                            rs.getLong("worker_user_id"),
                            rs.getString("status"),
                            rs.getObject("started_at", LocalDateTime.class)))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "work log not found", ex);
        }
    }

    private void requireWorkerAssignment(NodeRow node, BootstrapIdentity identity) {
        accessControlService.requireAssignedWorkerOrAdmin(identity, node.assignedUserId(), "worker cannot operate this node");
    }

    private void requireWorkLogOwner(WorkLogRow workLog, BootstrapIdentity identity) {
        // 开工 / 暂停 / 完工属「代操作生产」，是否允许派工权限持有者代做由配置开关决定（默认不允许）。
        accessControlService.requireProductionOperator(
                identity,
                workLog.workerUserId(),
                "worker cannot operate this work log",
                systemConfigService.adminCanOperateProduction());
    }

    private void requireProductionEquipmentWrite(BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, "production:equipment:write", "production equipment write requires production:equipment:write");
    }

    private void requirePermission(BootstrapIdentity identity, String permissionCode, String message) {
        accessControlService.requirePermission(identity, permissionCode, message);
    }

    private void requireAdminOrWorkerPermission(
            BootstrapIdentity identity, String permissionCode, String message) {
        requirePermission(identity, permissionCode, message);
        if (identity.role() != com.yuri.aiorder.common.UserRole.ADMIN
                && identity.role() != com.yuri.aiorder.common.UserRole.WORKER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "production portal access is required");
        }
    }

    private void requireProductionMaterialExceptionWrite(BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, "production:material:write", "production material exception write requires production:material:write");
    }

    private void requireProductionSafetyEnvironmentWrite(BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, "production:safety:write", "production safety environment write requires production:safety:write");
    }

    private void requireProductionCostWrite(BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, "production:cost:write", "production cost write requires production:cost:write");
    }

    private void requireProductionRewardPenaltyWrite(BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, "production:reward-penalty:write", "production reward penalty write requires production:reward-penalty:write");
    }

    private EquipmentInput normalizeProductionEquipment(ProductionEquipmentRequest request) {
        return new EquipmentInput(
                normalizeEquipmentCode(request.equipmentCode()),
                normalizeRequired(request.equipmentName(), "equipment_name"),
                normalizeCodeValue(request.equipmentType(), "equipment_type"),
                blankToNull(request.departmentName()),
                normalizeEquipmentStatus(request.status()),
                normalizeUtilizationRate(request.utilizationRate()));
    }

    private EquipmentEventInput normalizeProductionEquipmentEvent(ProductionEquipmentEventRequest request) {
        String description = blankToNull(request.description());
        if (description != null && description.length() > 512) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "description must be at most 512 characters");
        }
        return new EquipmentEventInput(
                normalizeEquipmentEventType(request.eventType()),
                normalizeEquipmentEventStatus(request.status()),
                normalizeDowntimeMinutes(request.downtimeMinutes()),
                description);
    }

    private String normalizeEquipmentCode(String value) {
        String normalized = normalizeCodeValue(value, "equipment_code");
        if (normalized.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "equipment_code must be at most 64 characters");
        }
        return normalized;
    }

    private String normalizeCodeValue(String value, String fieldName) {
        String normalized = normalizeRequired(value, fieldName).toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_\\-]{2,64}")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, fieldName + " must use A-Z, 0-9, underscore or hyphen");
        }
        return normalized;
    }

    private String normalizeEquipmentStatus(String value) {
        String normalized = value == null || value.isBlank() ? "IDLE" : value.trim().toUpperCase(Locale.ROOT);
        if (!EQUIPMENT_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported equipment status");
        }
        return normalized;
    }

    private String normalizeEquipmentEventType(String value) {
        String normalized = normalizeRequired(value, "event_type").toUpperCase(Locale.ROOT);
        if (!EQUIPMENT_EVENT_TYPES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported equipment event type");
        }
        return normalized;
    }

    private String normalizeEquipmentEventStatus(String value) {
        String normalized = value == null || value.isBlank() ? "PENDING" : value.trim().toUpperCase(Locale.ROOT);
        if (!EQUIPMENT_EVENT_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported equipment event status");
        }
        return normalized;
    }

    private double normalizeUtilizationRate(Double value) {
        double normalized = value == null ? 0.0 : value;
        if (normalized < 0.0 || normalized > 100.0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "utilization_rate must be between 0 and 100");
        }
        return BigDecimal.valueOf(normalized).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private int normalizeDowntimeMinutes(Integer value) {
        int normalized = value == null ? 0 : value;
        if (normalized < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "downtime_minutes cannot be negative");
        }
        return normalized;
    }

    private ProductionEquipmentResponse loadProductionEquipment(long equipmentId) {
        try {
            return jdbcClient.sql("""
                            SELECT equipment_id, equipment_code, equipment_name, equipment_type, department_name,
                                   status, owner_user_id, utilization_rate, last_maintenance_at,
                                   next_maintenance_at, created_at, updated_at
                            FROM production_equipment
                            WHERE equipment_id = :equipmentId
                            """)
                    .param("equipmentId", equipmentId)
                    .query(this::mapProductionEquipment)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "equipment not found", ex);
        }
    }

    private ProductionEquipmentResponse loadProductionEquipment(String equipmentCode) {
        try {
            return jdbcClient.sql("""
                            SELECT equipment_id, equipment_code, equipment_name, equipment_type, department_name,
                                   status, owner_user_id, utilization_rate, last_maintenance_at,
                                   next_maintenance_at, created_at, updated_at
                            FROM production_equipment
                            WHERE equipment_code = :equipmentCode
                            """)
                    .param("equipmentCode", equipmentCode)
                    .query(this::mapProductionEquipment)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "equipment not found", ex);
        }
    }

    private long findEquipmentIdByCode(String equipmentCode) {
        return jdbcClient.sql("""
                        SELECT equipment_id
                        FROM production_equipment
                        WHERE equipment_code = :equipmentCode
                        """)
                .param("equipmentCode", equipmentCode)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "equipment not found"));
    }

    private ProductionEquipmentEventResponse loadProductionEquipmentEvent(long eventId) {
        try {
            return jdbcClient.sql("""
                            SELECT ev.event_id, ev.equipment_id, e.equipment_code, ev.event_type, ev.status,
                                   ev.downtime_minutes, ev.description, ev.requested_by_user_id,
                                   ev.approved_by_user_id, ev.decision_note, ev.decided_at,
                                   ev.created_at, ev.resolved_at
                            FROM production_equipment_event ev
                            JOIN production_equipment e ON e.equipment_id = ev.equipment_id
                            WHERE ev.event_id = :eventId
                            """)
                    .param("eventId", eventId)
                    .query(this::mapProductionEquipmentEvent)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "equipment event not found", ex);
        }
    }

    private ProductionEquipmentResponse mapProductionEquipment(
            java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProductionEquipmentResponse(
                rs.getLong("equipment_id"),
                rs.getString("equipment_code"),
                rs.getString("equipment_name"),
                rs.getString("equipment_type"),
                rs.getString("department_name"),
                rs.getString("status"),
                rs.getObject("owner_user_id", Long.class),
                roundedDecimal(rs.getBigDecimal("utilization_rate")),
                rs.getObject("last_maintenance_at", LocalDateTime.class),
                rs.getObject("next_maintenance_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private ProductionEquipmentEventResponse mapProductionEquipmentEvent(
            java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProductionEquipmentEventResponse(
                rs.getLong("event_id"),
                rs.getLong("equipment_id"),
                rs.getString("equipment_code"),
                rs.getString("event_type"),
                rs.getString("status"),
                rs.getInt("downtime_minutes"),
                rs.getString("description"),
                rs.getObject("requested_by_user_id", Long.class),
                rs.getObject("approved_by_user_id", Long.class),
                rs.getString("decision_note"),
                rs.getObject("decided_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("resolved_at", LocalDateTime.class));
    }

    private MaterialExceptionInput normalizeProductionMaterialException(ProductionMaterialExceptionRequest request) {
        return new MaterialExceptionInput(
                normalizeExceptionNo(request.exceptionNo()),
                normalizeCodeValue(request.materialCode(), "material_code"),
                normalizeRequired(request.materialName(), "material_name"),
                request.orderId(),
                request.nodeInstanceId(),
                normalizeMaterialExceptionType(request.exceptionType()),
                normalizeMaterialExceptionStatus(request.status()),
                blankToNull(request.responsibilityOwner()),
                normalizeLossQuantity(request.lossQuantity()),
                normalizeOptionalDescription(request.description()));
    }

    private String normalizeExceptionNo(String value) {
        String normalized = normalizeCodeValue(value, "exception_no");
        if (normalized.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "exception_no must be at most 64 characters");
        }
        return normalized;
    }

    private String normalizeMaterialExceptionType(String value) {
        String normalized = normalizeRequired(value, "exception_type").toUpperCase(Locale.ROOT);
        if (!MATERIAL_EXCEPTION_TYPES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported material exception type");
        }
        return normalized;
    }

    private String normalizeMaterialExceptionStatus(String value) {
        String normalized = value == null || value.isBlank() ? "PENDING" : value.trim().toUpperCase(Locale.ROOT);
        if (!MATERIAL_EXCEPTION_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported material exception status");
        }
        return normalized;
    }

    private BigDecimal normalizeLossQuantity(BigDecimal value) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO : value;
        if (normalized.compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "loss_quantity cannot be negative");
        }
        return normalized.setScale(2, RoundingMode.HALF_UP);
    }

    private String normalizeOptionalDescription(String value) {
        String normalized = blankToNull(value);
        if (normalized != null && normalized.length() > 512) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "description must be at most 512 characters");
        }
        return normalized;
    }

    private ProductionMaterialExceptionResponse loadProductionMaterialException(long exceptionId) {
        try {
            return jdbcClient.sql("""
                            SELECT exception_id, exception_no, material_code, material_name, order_id, node_instance_id,
                                   exception_type, status, responsibility_owner, loss_quantity, description,
                                   created_at, updated_at, closed_at
                            FROM production_material_exception
                            WHERE exception_id = :exceptionId
                            """)
                    .param("exceptionId", exceptionId)
                    .query(this::mapProductionMaterialException)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "material exception not found", ex);
        }
    }

    private ProductionMaterialExceptionResponse loadProductionMaterialException(String exceptionNo) {
        try {
            return jdbcClient.sql("""
                            SELECT exception_id, exception_no, material_code, material_name, order_id, node_instance_id,
                                   exception_type, status, responsibility_owner, loss_quantity, description,
                                   created_at, updated_at, closed_at
                            FROM production_material_exception
                            WHERE exception_no = :exceptionNo
                            """)
                    .param("exceptionNo", exceptionNo)
                    .query(this::mapProductionMaterialException)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "material exception not found", ex);
        }
    }

    private ProductionMaterialExceptionResponse mapProductionMaterialException(
            java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProductionMaterialExceptionResponse(
                rs.getLong("exception_id"),
                rs.getString("exception_no"),
                rs.getString("material_code"),
                rs.getString("material_name"),
                rs.getObject("order_id", Long.class),
                rs.getObject("node_instance_id", Long.class),
                rs.getString("exception_type"),
                rs.getString("status"),
                rs.getString("responsibility_owner"),
                roundedDecimal(rs.getBigDecimal("loss_quantity"), 2),
                rs.getString("description"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("closed_at", LocalDateTime.class));
    }

    private SafetyEnvironmentEventInput normalizeProductionSafetyEnvironmentEvent(
            ProductionSafetyEnvironmentEventRequest request) {
        return new SafetyEnvironmentEventInput(
                normalizeSafetyEnvironmentEventNo(request.eventNo()),
                normalizeSafetyEnvironmentEventType(request.eventType()),
                normalizeSafetyEnvironmentEventStatus(request.status()),
                blankToNull(request.departmentName()),
                blankToNull(request.responsibleOwner()),
                blankToNull(request.equipmentCode()),
                normalizeSafetyEnvironmentRiskLevel(request.riskLevel()),
                request.dueAt(),
                normalizeOptionalDescription(request.description()));
    }

    private String normalizeSafetyEnvironmentEventNo(String value) {
        String normalized = normalizeCodeValue(value, "event_no");
        if (normalized.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "event_no must be at most 64 characters");
        }
        return normalized;
    }

    private String normalizeSafetyEnvironmentEventType(String value) {
        String normalized = normalizeRequired(value, "event_type").toUpperCase(Locale.ROOT);
        if (!SAFETY_ENVIRONMENT_EVENT_TYPES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported safety event type");
        }
        return normalized;
    }

    private String normalizeSafetyEnvironmentEventStatus(String value) {
        String normalized = value == null || value.isBlank() ? "PENDING" : value.trim().toUpperCase(Locale.ROOT);
        if (!SAFETY_ENVIRONMENT_EVENT_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported safety event status");
        }
        return normalized;
    }

    private String normalizeSafetyEnvironmentRiskLevel(String value) {
        String normalized = value == null || value.isBlank() ? "NORMAL" : value.trim().toUpperCase(Locale.ROOT);
        if (!SAFETY_ENVIRONMENT_RISK_LEVELS.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported safety risk level");
        }
        return normalized;
    }

    private ProductionSafetyEnvironmentEventResponse loadProductionSafetyEnvironmentEvent(long eventId) {
        try {
            return jdbcClient.sql("""
                            SELECT event_id, event_no, event_type, status, department_name, responsible_owner,
                                   equipment_code, risk_level, due_at, description, created_at, updated_at, closed_at
                            FROM production_safety_event
                            WHERE event_id = :eventId
                            """)
                    .param("eventId", eventId)
                    .query(this::mapProductionSafetyEnvironmentEvent)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "safety event not found", ex);
        }
    }

    private ProductionSafetyEnvironmentEventResponse loadProductionSafetyEnvironmentEvent(String eventNo) {
        try {
            return jdbcClient.sql("""
                            SELECT event_id, event_no, event_type, status, department_name, responsible_owner,
                                   equipment_code, risk_level, due_at, description, created_at, updated_at, closed_at
                            FROM production_safety_event
                            WHERE event_no = :eventNo
                            """)
                    .param("eventNo", eventNo)
                    .query(this::mapProductionSafetyEnvironmentEvent)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "safety event not found", ex);
        }
    }

    private ProductionSafetyEnvironmentEventResponse mapProductionSafetyEnvironmentEvent(
            java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProductionSafetyEnvironmentEventResponse(
                rs.getLong("event_id"),
                rs.getString("event_no"),
                rs.getString("event_type"),
                rs.getString("status"),
                rs.getString("department_name"),
                rs.getString("responsible_owner"),
                rs.getString("equipment_code"),
                rs.getString("risk_level"),
                rs.getObject("due_at", LocalDateTime.class),
                rs.getString("description"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("closed_at", LocalDateTime.class));
    }

    private CostRecordInput normalizeProductionCostRecord(ProductionCostRecordRequest request) {
        return new CostRecordInput(
                normalizeCostNo(request.costNo()),
                request.orderId(),
                request.nodeInstanceId(),
                normalizeProductionCostType(request.costType()),
                normalizeCostAmount(request.amount()),
                normalizeProductionCostStatus(request.status()),
                blankToNull(request.departmentName()),
                blankToNull(request.supplierName()),
                normalizeOptionalDescription(request.description()));
    }

    private String normalizeCostNo(String value) {
        String normalized = normalizeCodeValue(value, "cost_no");
        if (normalized.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cost_no must be at most 64 characters");
        }
        return normalized;
    }

    private String normalizeProductionCostType(String value) {
        String normalized = normalizeRequired(value, "cost_type").toUpperCase(Locale.ROOT);
        if (!PRODUCTION_COST_TYPES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported production cost type");
        }
        return normalized;
    }

    private String normalizeProductionCostStatus(String value) {
        String normalized = value == null || value.isBlank() ? "NORMAL" : value.trim().toUpperCase(Locale.ROOT);
        if (!PRODUCTION_COST_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported production cost status");
        }
        return normalized;
    }

    private BigDecimal normalizeCostAmount(BigDecimal value) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount is required");
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount cannot be negative");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private ProductionCostRecordResponse loadProductionCostRecord(long costId) {
        try {
            return jdbcClient.sql("""
                            SELECT cost_id, cost_no, order_id, node_instance_id, cost_type, amount, status,
                                   department_name, supplier_name, description, created_at, updated_at, confirmed_at
                            FROM production_cost_record
                            WHERE cost_id = :costId
                            """)
                    .param("costId", costId)
                    .query(this::mapProductionCostRecord)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "cost record not found", ex);
        }
    }

    private ProductionCostRecordResponse loadProductionCostRecord(String costNo) {
        try {
            return jdbcClient.sql("""
                            SELECT cost_id, cost_no, order_id, node_instance_id, cost_type, amount, status,
                                   department_name, supplier_name, description, created_at, updated_at, confirmed_at
                            FROM production_cost_record
                            WHERE cost_no = :costNo
                            """)
                    .param("costNo", costNo)
                    .query(this::mapProductionCostRecord)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "cost record not found", ex);
        }
    }

    private ProductionCostRecordResponse mapProductionCostRecord(
            java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProductionCostRecordResponse(
                rs.getLong("cost_id"),
                rs.getString("cost_no"),
                rs.getObject("order_id", Long.class),
                rs.getObject("node_instance_id", Long.class),
                rs.getString("cost_type"),
                roundedDecimal(rs.getBigDecimal("amount"), 2),
                rs.getString("status"),
                rs.getString("department_name"),
                rs.getString("supplier_name"),
                rs.getString("description"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("confirmed_at", LocalDateTime.class));
    }

    private JdbcClient.StatementSpec outsourcingQuery(String suffix) {
        return jdbcClient.sql("""
                        SELECT b.outsourcing_id, b.batch_no, b.order_id, o.order_no, o.product_type,
                               b.item_name, b.supplier_name, b.quantity, b.status, b.sent_at,
                               b.expected_return_at, b.actual_return_at, b.abnormal_note,
                               b.created_at, b.updated_at,
                               CASE
                                 WHEN b.status <> 'RETURNED'
                                      AND b.expected_return_at IS NOT NULL
                                      AND b.expected_return_at < CURRENT_TIMESTAMP(3) THEN 1
                                 ELSE 0
                               END AS is_overdue
                        FROM production_outsourcing_batch b
                        JOIN orders o ON o.order_id = b.order_id
                        """ + suffix);
    }

    private ProductionOutsourcingBatchResponse mapProductionOutsourcingBatch(
            java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProductionOutsourcingBatchResponse(
                rs.getLong("outsourcing_id"),
                rs.getString("batch_no"),
                rs.getLong("order_id"),
                rs.getString("order_no"),
                rs.getString("product_type"),
                rs.getString("item_name"),
                rs.getString("supplier_name"),
                rs.getInt("quantity"),
                rs.getString("status"),
                rs.getObject("sent_at", LocalDateTime.class),
                rs.getObject("expected_return_at", LocalDateTime.class),
                rs.getObject("actual_return_at", LocalDateTime.class),
                rs.getBoolean("is_overdue"),
                rs.getString("abnormal_note"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private RewardPenaltyRecordInput normalizeProductionRewardPenaltyRecord(
            ProductionRewardPenaltyRecordRequest request) {
        return new RewardPenaltyRecordInput(
                normalizeRewardPenaltyRecordNo(request.recordNo()),
                normalizeProductionRewardPenaltyType(request.recordType()),
                normalizeProductionRewardPenaltyReasonCategory(request.reasonCategory()),
                normalizeRewardPenaltyAmount(request.amount()),
                normalizeProductionRewardPenaltyStatus(request.status()),
                request.orderId(),
                request.nodeInstanceId(),
                request.employeeUserId(),
                blankToNull(request.departmentName()),
                normalizeOptionalDescription(request.description()));
    }

    private String normalizeRewardPenaltyRecordNo(String value) {
        String normalized = normalizeCodeValue(value, "record_no");
        if (normalized.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "record_no must be at most 64 characters");
        }
        return normalized;
    }

    private String normalizeProductionRewardPenaltyType(String value) {
        String normalized = normalizeRequired(value, "record_type").toUpperCase(Locale.ROOT);
        if (!PRODUCTION_REWARD_PENALTY_TYPES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported reward penalty type");
        }
        return normalized;
    }

    private String normalizeProductionRewardPenaltyReasonCategory(String value) {
        String normalized = normalizeRequired(value, "reason_category").toUpperCase(Locale.ROOT);
        if (!PRODUCTION_REWARD_PENALTY_REASON_CATEGORIES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported reward penalty reason category");
        }
        return normalized;
    }

    private String normalizeProductionRewardPenaltyStatus(String value) {
        String normalized = value == null || value.isBlank() ? "PENDING" : value.trim().toUpperCase(Locale.ROOT);
        if (!PRODUCTION_REWARD_PENALTY_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported reward penalty status");
        }
        return normalized;
    }

    private BigDecimal normalizeRewardPenaltyAmount(BigDecimal value) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount is required");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private boolean isApprovedRewardPenaltyStatus(String status) {
        return "APPROVED".equals(status) || "EFFECTIVE".equals(status);
    }

    private ProductionRewardPenaltyRecordResponse loadProductionRewardPenaltyRecord(long recordId) {
        try {
            return jdbcClient.sql("""
                            SELECT record_id, record_no, record_type, reason_category, amount, status,
                                   order_id, node_instance_id, employee_user_id, approver_user_id,
                                   department_name, description, created_at, updated_at, approved_at, effective_at
                            FROM production_reward_penalty_record
                            WHERE record_id = :recordId
                            """)
                    .param("recordId", recordId)
                    .query(this::mapProductionRewardPenaltyRecord)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "reward penalty record not found", ex);
        }
    }

    private ProductionRewardPenaltyRecordResponse loadProductionRewardPenaltyRecord(String recordNo) {
        try {
            return jdbcClient.sql("""
                            SELECT record_id, record_no, record_type, reason_category, amount, status,
                                   order_id, node_instance_id, employee_user_id, approver_user_id,
                                   department_name, description, created_at, updated_at, approved_at, effective_at
                            FROM production_reward_penalty_record
                            WHERE record_no = :recordNo
                            """)
                    .param("recordNo", recordNo)
                    .query(this::mapProductionRewardPenaltyRecord)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "reward penalty record not found", ex);
        }
    }

    private ProductionRewardPenaltyRecordResponse mapProductionRewardPenaltyRecord(
            java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProductionRewardPenaltyRecordResponse(
                rs.getLong("record_id"),
                rs.getString("record_no"),
                rs.getString("record_type"),
                rs.getString("reason_category"),
                roundedDecimal(rs.getBigDecimal("amount"), 2),
                rs.getString("status"),
                rs.getObject("order_id", Long.class),
                rs.getObject("node_instance_id", Long.class),
                rs.getObject("employee_user_id", Long.class),
                rs.getObject("approver_user_id", Long.class),
                rs.getString("department_name"),
                rs.getString("description"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("approved_at", LocalDateTime.class),
                rs.getObject("effective_at", LocalDateTime.class));
    }

    private String normalizeCheckType(int checkType) {
        return switch (checkType) {
            case 1 -> "IN";
            case 2 -> "OUT";
            // 3 = 过程抽检：不参与一次通过率 / 终检通过率统计（那两项只看 check_type = 'OUT'）。
            case 3 -> "SAMPLE";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported check_type");
        };
    }

    private Integer denormalizeCheckType(String checkType) {
        if ("IN".equals(checkType)) {
            return 1;
        }
        if ("OUT".equals(checkType)) {
            return 2;
        }
        if ("SAMPLE".equals(checkType)) {
            return 3;
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String serializeImpactedNodeInstanceIds(List<Long> nodeInstanceIds) {
        try {
            return objectMapper.writeValueAsString(nodeInstanceIds);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "failed to build rework impact audit payload", ex);
        }
    }

    private List<Long> parseImpactedNodeInstanceIds(String payload) {
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(payload, LONG_LIST_TYPE);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "failed to parse rework impact audit payload", ex);
        }
    }

    private String normalizeDictionaryValue(
            String value, String dictionaryType, String unsupportedMessage) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        String upper = normalized.toUpperCase(Locale.ROOT);
        boolean supported = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM rework_dictionary_item
                        WHERE dictionary_type = :dictionaryType
                          AND item_code = :code
                          AND status = 'ACTIVE'
                        """)
                .param("dictionaryType", dictionaryType)
                .param("code", upper)
                .query(Long.class)
                .single() > 0;
        if (!supported) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, unsupportedMessage);
        }
        return upper;
    }

    private List<ReworkDictionaryOption> listActiveReworkDictionaryOptions(String dictionaryType) {
        return jdbcClient.sql("""
                        SELECT item_code, item_label
                        FROM rework_dictionary_item
                        WHERE dictionary_type = :dictionaryType
                          AND status = 'ACTIVE'
                        ORDER BY sort_order, item_id
                        """)
                .param("dictionaryType", dictionaryType)
                .query((rs, rowNum) -> new ReworkDictionaryOption(
                        rs.getString("item_code"),
                        rs.getString("item_label")))
                .list();
    }

    private ReworkDictionaryItemResponse requireReworkDictionaryItem(long itemId) {
        return jdbcClient.sql("""
                        SELECT item_id, dictionary_type, item_code, item_label, sort_order, status
                        FROM rework_dictionary_item
                        WHERE item_id = :itemId
                        """)
                .param("itemId", itemId)
                .query((rs, rowNum) -> new ReworkDictionaryItemResponse(
                        rs.getLong("item_id"),
                        rs.getString("dictionary_type"),
                        rs.getString("item_code"),
                        rs.getString("item_label"),
                        rs.getInt("sort_order"),
                        rs.getString("status")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "rework dictionary item not found"));
    }

    private String normalizeReworkDictionaryType(String dictionaryType) {
        String normalized = normalizeRequired(dictionaryType, "dictionary_type").toUpperCase(Locale.ROOT);
        if (!REWORK_DICTIONARY_TYPES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported rework dictionary type");
        }
        return normalized;
    }

    private String normalizeReworkDictionaryStatus(String status) {
        String normalized = normalizeRequired(status, "status").toUpperCase(Locale.ROOT);
        if (!REWORK_DICTIONARY_STATUS.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported rework dictionary status");
        }
        return normalized;
    }

    private String normalizeRequired(String value, String fieldName) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
        return normalized;
    }

    private long lastInsertId() {
        return jdbcClient.sql("SELECT LAST_INSERT_ID()")
                .query(Long.class)
                .single();
    }

    private long countLong(String sql, Long userId) {
        return jdbcClient.sql(sql)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    private long countLong(String sql, Long userId, PerformancePeriodFilter period) {
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql)
                .param("userId", userId);
        statement = bindPeriod(statement, period);
        return statement.query(Long.class)
                .single();
    }

    private PerformancePeriodFilter performancePeriodFilter(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "end_date cannot be before start_date");
        }
        LocalDateTime startAt = startDate == null ? null : startDate.atStartOfDay();
        LocalDateTime endExclusive = endDate == null ? null : endDate.plusDays(1).atStartOfDay();
        return new PerformancePeriodFilter(startAt, endExclusive);
    }

    private String periodSql(PerformancePeriodFilter period, String columnName) {
        StringBuilder sql = new StringBuilder();
        if (period.startAt() != null) {
            sql.append(" AND ").append(columnName).append(" >= :periodStartAt\n");
        }
        if (period.endExclusive() != null) {
            sql.append(" AND ").append(columnName).append(" < :periodEndExclusive\n");
        }
        return sql.toString();
    }

    private JdbcClient.StatementSpec bindPeriod(JdbcClient.StatementSpec statement, PerformancePeriodFilter period) {
        if (period.startAt() != null) {
            statement = statement.param("periodStartAt", period.startAt());
        }
        if (period.endExclusive() != null) {
            statement = statement.param("periodEndExclusive", period.endExclusive());
        }
        return statement;
    }

    private int percent(long part, long total) {
        if (total == 0) {
            return 0;
        }
        return Math.toIntExact(Math.round((part * 100.0) / total));
    }

    private int performanceScore(
            int durationEfficiency,
            int passRate,
            int onTimeRate,
            long responsibleReworkCount,
            long unclassifiedReworkCount) {
        int responsibilityScore = Math.max(
                0,
                100
                        - Math.toIntExact(Math.min(responsibleReworkCount * 10, 100))
                        - Math.toIntExact(Math.min(unclassifiedReworkCount * 5, 100)));
        double cappedDurationEfficiency = Math.min(Math.max(durationEfficiency, 0), 120);
        return Math.toIntExact(Math.round(
                cappedDurationEfficiency * 0.4
                        + passRate * 0.3
                        + onTimeRate * 0.2
                        + responsibilityScore * 0.1));
    }

    private double percentage(long part, long total) {
        if (total == 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(part * 100.0 / total)
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private double roundedDecimal(BigDecimal value) {
        return roundedDecimal(value, 1);
    }

    private double roundedDecimal(BigDecimal value, int scale) {
        if (value == null) {
            return 0.0;
        }
        return value.setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private record NodeRow(
            long nodeInstanceId,
            long instanceId,
            long orderId,
            Long assignedUserId,
            String nodeStatus) {
    }

    private record WorkLogRow(
            long workLogId,
            long workerUserId,
            String status,
            LocalDateTime startedAt) {
    }

    private record ReworkRow(
            long reworkId,
            long sourceCheckId,
            long targetNodeInstanceId,
            String status) {
    }

    private record QualityCheckSummaryRow(
            long inspectedOrderCount,
            long firstPassCount,
            long finalPassCount) {
    }

    private record QualityReworkSummaryRow(
            long totalReworkCount,
            long internalReworkCount,
            long externalReworkCount,
            long unclassifiedReworkCount) {
    }

    private record WorkbenchDepartmentDefinition(
            String key,
            String name,
            String subtitle,
            int displayOrder) {
    }

    private record WorkbenchStatus(String code, String label) {
    }

    private static final class WorkbenchDepartmentAccumulator {
        private final Map<LocalDate, WorkbenchDayStats> days = new HashMap<>();
        private long activeNodes;

        private WorkbenchDayStats day(LocalDate date) {
            return days.computeIfAbsent(date, ignored -> new WorkbenchDayStats());
        }

        private long completedWorkLogs(YearMonth month) {
            return days.entrySet().stream()
                    .filter(entry -> YearMonth.from(entry.getKey()).equals(month))
                    .mapToLong(entry -> entry.getValue().completedWorkLogs)
                    .sum();
        }

        private long completedWorkLogDays(YearMonth month) {
            return days.entrySet().stream()
                    .filter(entry -> YearMonth.from(entry.getKey()).equals(month))
                    .filter(entry -> entry.getValue().completedWorkLogs > 0)
                    .count();
        }

        private long completedNodes(YearMonth month) {
            return days.entrySet().stream()
                    .filter(entry -> YearMonth.from(entry.getKey()).equals(month))
                    .mapToLong(entry -> entry.getValue().completedNodes)
                    .sum();
        }

        private long reworkCount(YearMonth month) {
            return days.entrySet().stream()
                    .filter(entry -> YearMonth.from(entry.getKey()).equals(month))
                    .mapToLong(entry -> entry.getValue().reworkCount)
                    .sum();
        }
    }

    private static final class WorkbenchDayStats {
        private long completedWorkLogs;
        private long completedNodes;
        private long reworkCount;
    }

    private record EquipmentSummaryRow(
            long totalEquipmentCount,
            long runningCount,
            long idleCount,
            long maintenanceCount,
            long faultCount,
            double averageUtilizationRate) {
    }

    private record EquipmentEventSummaryRow(
            long pendingMaintenanceCount,
            long openFaultCount,
            long downtimeMinutes) {
    }

    private record EquipmentInput(
            String equipmentCode,
            String equipmentName,
            String equipmentType,
            String departmentName,
            String status,
            double utilizationRate) {
    }

    private record EquipmentEventInput(
            String eventType,
            String status,
            int downtimeMinutes,
            String description) {
    }

    private record MaterialExceptionSummaryRow(
            long totalExceptionCount,
            long currentMonthCount,
            long previousMonthCount,
            long shortageCount,
            long wrongMaterialCount,
            long batchAbnormalCount,
            long materialLossCount,
            long pendingCount,
            long inProgressCount,
            long closedCount,
            long responsibilityAssignedCount,
            double totalLossQuantity) {
    }

    private record MaterialExceptionInput(
            String exceptionNo,
            String materialCode,
            String materialName,
            Long orderId,
            Long nodeInstanceId,
            String exceptionType,
            String status,
            String responsibilityOwner,
            BigDecimal lossQuantity,
            String description) {
    }

    private record SafetyEnvironmentSummaryRow(
            long totalEventCount,
            long safetyInspectionCount,
            long hazardRectificationCount,
            long environmentRecordCount,
            long ppeDeviceReminderCount,
            long pendingCount,
            long inProgressCount,
            long closedCount,
            long overdueCount,
            long highRiskCount) {
    }

    private record SafetyEnvironmentEventInput(
            String eventNo,
            String eventType,
            String status,
            String departmentName,
            String responsibleOwner,
            String equipmentCode,
            String riskLevel,
            LocalDateTime dueAt,
            String description) {
    }

    private record CostSummaryRow(
            long recordCount,
            double totalCostAmount,
            double processCostAmount,
            double materialCostAmount,
            double laborCostAmount,
            double reworkCostAmount,
            double outsourcingCostAmount,
            long abnormalWarningCount) {
    }

    private record CostRecordInput(
            String costNo,
            Long orderId,
            Long nodeInstanceId,
            String costType,
            BigDecimal amount,
            String status,
            String departmentName,
            String supplierName,
            String description) {
    }

    private record RewardPenaltySummaryRow(
            long totalRecordCount,
            long rewardCount,
            long penaltyCount,
            long pendingCount,
            long approvedCount,
            long rejectedCount,
            long effectiveCount,
            long relatedOrderCount,
            long relatedProcessCount,
            long relatedEmployeeCount,
            double monthlyAmount) {
    }

    private record RewardPenaltyRecordInput(
            String recordNo,
            String recordType,
            String reasonCategory,
            BigDecimal amount,
            String status,
            Long orderId,
            Long nodeInstanceId,
            Long employeeUserId,
            String departmentName,
            String description) {
    }

    private record PerformancePeriodFilter(
            LocalDateTime startAt,
            LocalDateTime endExclusive) {
    }

    private record ReworkNotificationRow(
            long reworkId,
            long orderId,
            String orderNo,
            Long csUserId,
            long targetNodeInstanceId) {
    }

    private record ReworkNotificationPayload(
            String event,
            long orderId,
            String orderNo,
            String message,
            long reworkId,
            long targetNodeInstanceId) {
    }

    private record FinalCheckRow(long checkId) {
    }
}
