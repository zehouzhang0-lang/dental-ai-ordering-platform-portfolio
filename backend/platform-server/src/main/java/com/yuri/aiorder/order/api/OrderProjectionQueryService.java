package com.yuri.aiorder.order.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.auth.AccessControlService;
import com.yuri.aiorder.order.rules.DeliveryPlanService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderProjectionQueryService {

    private static final List<DoctorProgressMilestone> DOCTOR_PROGRESS_MILESTONES = List.of(
            new DoctorProgressMilestone("review", "资料审核", "PENDING_REVIEW", 0, "订单资料正在审核"),
            new DoctorProgressMilestone("design", "方案设计", "DESIGNING", 1, "订单已通过审核，正在进行方案设计"),
            new DoctorProgressMilestone("production", "制作处理中", "PRODUCING", 2, "方案已确认，正在制作"),
            new DoctorProgressMilestone("final-review", "成品复核", "QC", 3, "成品正在复核"),
            new DoctorProgressMilestone("ready-to-ship", "待发货", "PENDING_SHIP", 4, "成品已完成，等待发货"),
            new DoctorProgressMilestone("shipped", "配送中", "SHIPPED", 5, "订单已发货，请在物流页面查看配送信息"),
            new DoctorProgressMilestone("completed", "已完成", "COMPLETED", 6, "订单已完成"));

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;
    private final DeliveryPlanService deliveryPlanService;

    public OrderProjectionQueryService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            AccessControlService accessControlService,
            DeliveryPlanService deliveryPlanService) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
        this.deliveryPlanService = deliveryPlanService;
    }

    public DoctorOrderVO getDoctorOrder(long orderId, BootstrapIdentity identity) {
        OrderReadRow row = loadOrder(orderId, identity, "doctor cannot access this order");
        return toDoctorOrder(row, doctorPublicProgress(row));
    }

    public OrderInternalDTO getInternalOrder(long orderId, BootstrapIdentity identity) {
        OrderReadRow row = loadOrder(orderId, identity, "identity cannot access this order");
        return toInternalOrder(row);
    }

    public JsonNode getNormalizedFormData(long orderId, BootstrapIdentity identity) {
        OrderReadRow row = loadOrder(orderId, identity, "identity cannot access this order");
        return internalFormData(row);
    }

    public OrderListResponse<?> listOrders(
            BootstrapIdentity identity, String externalStatus, String internalStatus, String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(size, 100));
        int offset = (safePage - 1) * safeSize;
        String dataScope = accessControlService.effectiveDataScope(identity);
        accessControlService.requireScopedIdentity(identity, dataScope);

        List<String> filters = new ArrayList<>();
        filters.add(scopedWhereClause(identity));
        if (externalStatus != null && !externalStatus.isBlank()) {
            filters.add("o.external_status = :externalStatus");
        }
        if (!identity.isDoctor() && internalStatus != null && !internalStatus.isBlank()) {
            filters.add("o.internal_status = :internalStatus");
        }
        if (keyword != null && !keyword.isBlank()) {
            filters.add("""
                    (o.order_no LIKE :keyword
                     OR c.clinic_name LIKE :keyword
                     OR doctor.display_name LIKE :keyword
                     OR patient.patient_name LIKE :keyword
                     OR o.product_type LIKE :keyword
                     OR JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.patient_name')) LIKE :keyword
                     OR JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.tooth_position')) LIKE :keyword
                     OR JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.tooth_positions')) LIKE :keyword
                     OR JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.tooth')) LIKE :keyword
                     OR JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.teeth')) LIKE :keyword
                     OR JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.material')) LIKE :keyword
                     OR JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.material_name')) LIKE :keyword
                     OR JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.material_spec')) LIKE :keyword
                     OR JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.shade')) LIKE :keyword
                     OR JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.color')) LIKE :keyword
                     OR JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.shade_code')) LIKE :keyword
                     OR JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.customer_case_no')) LIKE :keyword
                     OR JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.customer_case_number')) LIKE :keyword
                     OR JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.case_no')) LIKE :keyword
                     OR JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.case_number')) LIKE :keyword
                     OR JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.customer_order_no')) LIKE :keyword
                     OR JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.clinic_order_no')) LIKE :keyword
                     OR JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.external_order_no')) LIKE :keyword
                     OR JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.doctor_case_no')) LIKE :keyword
                     OR JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.patient_code')) LIKE :keyword)
                    """);
        }
        String whereClause = "WHERE " + String.join(" AND ", filters);

        List<OrderReadRow> rows = bindListParams(queryOrderRows(whereClause), identity, dataScope, externalStatus, internalStatus, keyword)
                .param("limit", safeSize)
                .param("offset", offset)
                .query(this::mapOrder)
                .list();
        long total = bindListParams(countOrders(whereClause), identity, dataScope, externalStatus, internalStatus, keyword)
                .query(Long.class)
                .single();

        if (identity.isDoctor()) {
            return new OrderListResponse<>(rows.stream().map(row -> toDoctorOrder(row, List.of())).toList(), total, safePage, safeSize);
        }
        return new OrderListResponse<>(rows.stream().map(this::toInternalOrder).toList(), total, safePage, safeSize);
    }

    public DoctorOrderAssistantReadModel getAssistantReadModel(long orderId, BootstrapIdentity identity) {
        OrderReadRow row = loadOrder(orderId, identity, "doctor cannot access this order");
        return new DoctorOrderAssistantReadModel(
                row.orderId(),
                row.orderNo(),
                row.externalStatus(),
                row.publicMessage(),
                row.billStatus(),
                row.logisticsStatus(),
                row.trackingNo(),
                doctorVisibleMessageSummary(orderId));
    }

    private OrderReadRow loadOrder(long orderId, BootstrapIdentity identity, String forbiddenMessage) {
        String dataScope = accessControlService.effectiveDataScope(identity);
        accessControlService.requireScopedIdentity(identity, dataScope);
        try {
            return queryOrder("""
                            WHERE o.order_id = :orderId
                              AND %s
                            """.formatted(scopedWhereClause(identity)), orderId, identity, dataScope);
        } catch (EmptyResultDataAccessException ex) {
            if (orderExists(orderId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, forbiddenMessage, ex);
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found", ex);
        }
    }

    private String scopedWhereClause(BootstrapIdentity identity) {
        if (identity.isDoctor()) {
            return "o.doctor_user_id = :userId";
        }
        return """
                (
                    :dataScope = 'ALL'
                    OR (:dataScope = 'CLINIC'
                        AND (o.clinic_id = :clinicId OR o.doctor_user_id = :userId))
                    OR (:dataScope = 'SELF'
                        AND (
                            o.doctor_user_id = :userId
                            OR o.cs_user_id = :userId
                            OR EXISTS (
                                SELECT 1
                                FROM order_process_instance scoped_i
                                JOIN order_process_node scoped_n
                                  ON scoped_n.instance_id = scoped_i.instance_id
                                WHERE scoped_i.order_id = o.order_id
                                  AND scoped_n.assigned_user_id = :userId
                            )
                            OR EXISTS (
                                SELECT 1
                                FROM design_task scoped_design
                                WHERE scoped_design.order_id = o.order_id
                                  AND scoped_design.assigned_user_id = :userId
                                  AND scoped_design.task_status <> 'CANCELLED'
                            )
                        ))
                    OR (:canReviewProduction = TRUE
                        AND o.internal_status = 'PENDING_PRODUCTION_REVIEW')
                )
                """;
    }

    private JdbcClient.StatementSpec queryOrderRows(String whereClause) {
        return jdbcClient.sql("""
                        %s
                        %s
                        ORDER BY o.created_at DESC, o.order_id DESC
                        LIMIT :limit OFFSET :offset
                        """.formatted(baseOrderSelect(), whereClause));
    }

    private JdbcClient.StatementSpec countOrders(String whereClause) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM orders o
                        JOIN clinic c ON c.clinic_id = o.clinic_id
                        LEFT JOIN system_user doctor ON doctor.user_id = o.doctor_user_id
                        LEFT JOIN patient_record patient ON patient.patient_id = o.patient_id
                        %s
                        """.formatted(whereClause));
    }

    private JdbcClient.StatementSpec bindListParams(
            JdbcClient.StatementSpec spec,
            BootstrapIdentity identity,
            String dataScope,
            String externalStatus,
            String internalStatus,
            String keyword) {
        spec = spec.param("dataScope", dataScope)
                .param("userId", identity.userId())
                .param("clinicId", identity.clinicId())
                .param("confirmationGraceDays", deliveryPlanService.doctorConfirmationGraceDays())
                .param("canReviewProduction", accessControlService.canReviewProduction(identity));
        if (externalStatus != null && !externalStatus.isBlank()) {
            spec = spec.param("externalStatus", externalStatus);
        }
        if (!identity.isDoctor() && internalStatus != null && !internalStatus.isBlank()) {
            spec = spec.param("internalStatus", internalStatus.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            spec = spec.param("keyword", "%" + keyword.trim() + "%");
        }
        return spec;
    }

    private OrderReadRow loadOrder(long orderId) {
        try {
            return queryOrder("WHERE o.order_id = :orderId", orderId, null, null);
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found", ex);
        }
    }

    private OrderReadRow queryOrder(
            String whereClause,
            long orderId,
            BootstrapIdentity identity,
            String dataScope) {
        JdbcClient.StatementSpec spec = jdbcClient.sql("""
                        %s
                        %s
                        """.formatted(baseOrderSelect(), whereClause))
                .param("orderId", orderId)
                // 宽限期是配置项，SQL 里的超期判定必须用当前配置值，因此每次查询都绑定。
                .param("confirmationGraceDays", deliveryPlanService.doctorConfirmationGraceDays());
        if (identity != null) {
            spec = spec.param("dataScope", dataScope)
                    .param("userId", identity.userId())
                    .param("clinicId", identity.clinicId())
                    .param("canReviewProduction", accessControlService.canReviewProduction(identity));
        }
        return spec.query(this::mapOrder).single();
    }

    private String baseOrderSelect() {
        return """
                SELECT
                    o.order_id,
                    o.order_no,
                    o.group_id,
                    o.clinic_id,
                    c.clinic_name,
                    o.doctor_user_id,
                    doctor.display_name AS doctor_name,
                    o.patient_id,
                    COALESCE(
                        patient.patient_name,
                        JSON_UNQUOTE(JSON_EXTRACT(o.form_data, '$.patient_name'))
                    ) AS patient_name,
                    o.cs_user_id,
                    o.product_type,
                    o.internal_status,
                    o.external_status,
                    o.production_note,
                    o.reject_reason,
                    o.form_data,
                    snapshot.product_snapshot AS catalog_product_snapshot,
                    snapshot.form_schema_snapshot,
                    snapshot.normalized_form_values,
                    o.created_at,
                    o.updated_at,
                    p.public_message,
                    b.bill_status,
                    l.logistics_status,
                    l.tracking_no,
                    plan.computed_delivery_date,
                    plan.doctor_requested_delivery_date,
                    plan.variance_days,
                    plan.variance_flag,
                    plan.waiting_days,
                    plan.estimate_status AS delivery_estimate_status,
                    EXISTS (
                        SELECT 1
                        FROM order_process_confirmation overdue
                        WHERE overdue.order_id = o.order_id
                          AND overdue.confirmation_status = 'AWAITING_DOCTOR'
                          AND overdue.requested_at IS NOT NULL
                          AND DATE_ADD(DATE(overdue.requested_at), INTERVAL :confirmationGraceDays DAY)
                              < CURRENT_DATE()
                    ) AS confirmation_overdue
                FROM orders o
                JOIN clinic c ON c.clinic_id = o.clinic_id
                LEFT JOIN system_user doctor ON doctor.user_id = o.doctor_user_id
                LEFT JOIN patient_record patient ON patient.patient_id = o.patient_id
                LEFT JOIN order_external_projection p ON p.order_id = o.order_id
                LEFT JOIN order_catalog_snapshot snapshot ON snapshot.order_id = o.order_id
                LEFT JOIN order_bill b ON b.order_id = o.order_id
                LEFT JOIN order_logistics l ON l.order_id = o.order_id
                LEFT JOIN order_delivery_plan plan ON plan.order_id = o.order_id
                """;
    }

    private OrderReadRow mapOrder(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new OrderReadRow(
                rs.getLong("order_id"),
                rs.getString("order_no"),
                rs.getObject("group_id", Long.class),
                rs.getLong("clinic_id"),
                rs.getString("clinic_name"),
                rs.getObject("doctor_user_id", Long.class),
                rs.getString("doctor_name"),
                rs.getObject("patient_id", Long.class),
                rs.getString("patient_name"),
                rs.getObject("cs_user_id", Long.class),
                rs.getString("product_type"),
                rs.getString("internal_status"),
                rs.getString("external_status"),
                rs.getString("production_note"),
                rs.getString("reject_reason"),
                rs.getString("form_data"),
                rs.getString("catalog_product_snapshot"),
                rs.getString("form_schema_snapshot"),
                rs.getString("normalized_form_values"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getString("public_message"),
                rs.getString("bill_status"),
                rs.getString("logistics_status"),
                rs.getString("tracking_no"),
                rs.getObject("computed_delivery_date", LocalDate.class),
                rs.getObject("doctor_requested_delivery_date", LocalDate.class),
                rs.getObject("variance_days", Integer.class),
                rs.getString("variance_flag"),
                rs.getInt("waiting_days"),
                rs.getString("delivery_estimate_status"),
                rs.getBoolean("confirmation_overdue"));
    }

    private DoctorOrderVO toDoctorOrder(OrderReadRow row, List<DoctorOrderProgressItem> publicProgress) {
        return new DoctorOrderVO(
                row.orderId(),
                row.orderNo(),
                row.groupId(),
                row.patientId(),
                row.productType(),
                row.externalStatus(),
                isDoctorEditable(row.internalStatus()),
                internalFormData(row),
                row.publicMessage(),
                row.billStatus(),
                row.logisticsStatus(),
                row.trackingNo(),
                row.createdAt(),
                row.updatedAt(),
                publicProgress);
    }

    private List<DoctorOrderProgressItem> doctorPublicProgress(OrderReadRow row) {
        int currentRank = doctorExternalStatusRank(row.externalStatus());
        Map<String, LocalDateTime> occurredAtByStatus = new HashMap<>();
        jdbcClient.sql("""
                        SELECT to_external_status, MIN(created_at) AS occurred_at
                        FROM order_status_history
                        WHERE order_id = :orderId
                        GROUP BY to_external_status
                        """)
                .param("orderId", row.orderId())
                .query((rs, rowNum) -> new DoctorStatusOccurrence(
                        rs.getString("to_external_status"),
                        rs.getObject("occurred_at", LocalDateTime.class)))
                .list()
                .forEach(item -> occurredAtByStatus.put(item.externalStatus(), item.occurredAt()));

        List<DoctorOrderProgressItem> result = new ArrayList<>();
        result.add(new DoctorOrderProgressItem(
                "submitted",
                currentRank < 0 ? "订单待提交" : "订单已提交",
                currentRank < 0 ? "ACTIVE" : "DONE",
                row.createdAt(),
                currentRank < 0 ? "订单仍为草稿，提交后进入资料审核" : "订单已进入公开处理流程"));

        for (DoctorProgressMilestone milestone : DOCTOR_PROGRESS_MILESTONES) {
            String status;
            if (currentRank > milestone.rank()) {
                status = "DONE";
            } else if (currentRank == milestone.rank()) {
                status = "COMPLETED".equals(milestone.externalStatus()) ? "DONE" : "ACTIVE";
            } else {
                status = "PENDING";
            }
            LocalDateTime occurredAt = occurredAtByStatus.get(milestone.externalStatus());
            if (occurredAt == null && currentRank == milestone.rank()) {
                occurredAt = row.updatedAt();
            }
            if (occurredAt == null && "PENDING_REVIEW".equals(milestone.externalStatus()) && currentRank >= 0) {
                occurredAt = row.createdAt();
            }
            result.add(new DoctorOrderProgressItem(
                    milestone.key(),
                    milestone.label(),
                    status,
                    occurredAt,
                    "ACTIVE".equals(status) ? milestone.activeNote() : null));
        }
        return result;
    }

    private int doctorExternalStatusRank(String externalStatus) {
        if (externalStatus == null || "DRAFT".equals(externalStatus)) {
            return -1;
        }
        for (DoctorProgressMilestone milestone : DOCTOR_PROGRESS_MILESTONES) {
            if (milestone.externalStatus().equals(externalStatus)) {
                return milestone.rank();
            }
        }
        return 0;
    }

    private boolean isDoctorEditable(String internalStatus) {
        return "DRAFT".equals(internalStatus)
                || "CS_REJECTED".equals(internalStatus)
                || "PRODUCTION_REJECTED".equals(internalStatus);
    }

    private OrderInternalDTO toInternalOrder(OrderReadRow row) {
        return new OrderInternalDTO(
                row.orderId(),
                row.orderNo(),
                row.clinicId(),
                row.clinicName(),
                row.doctorUserId(),
                row.doctorName(),
                row.patientId(),
                row.patientName(),
                row.csUserId(),
                row.productType(),
                row.internalStatus(),
                row.externalStatus(),
                row.productionNote(),
                row.rejectReason(),
                row.formSchemaSnapshot() == null ? null : readJson(row.formSchemaSnapshot()),
                internalFormData(row),
                row.computedDeliveryDate(),
                row.doctorRequestedDeliveryDate(),
                row.varianceDays(),
                deliveryAlert(row),
                deliveryAlertMessage(row),
                row.deliveryEstimateStatus(),
                row.createdAt(),
                row.updatedAt());
    }

    /**
     * 时间异常提示。医生把到货时间调早于系统可行交期是最需要客服介入的一类，因此优先级最高；
     * 其次是过程确认超期未回复导致的等待。没有交期计划（F 批次之前的订单）时不提示。
     */
    private String deliveryAlert(OrderReadRow row) {
        if ("EARLIER_THAN_FEASIBLE".equals(row.varianceFlag())) {
            return "EARLIER_THAN_FEASIBLE";
        }
        if (row.confirmationOverdue()) {
            return "WAITING_DOCTOR_CONFIRMATION";
        }
        if ("LATER_THAN_PLAN".equals(row.varianceFlag())) {
            return "LATER_THAN_PLAN";
        }
        return null;
    }

    private String deliveryAlertMessage(OrderReadRow row) {
        String alert = deliveryAlert(row);
        if (alert == null) {
            return null;
        }
        return switch (alert) {
            case "EARLIER_THAN_FEASIBLE" -> "医生要求到货 " + row.doctorRequestedDeliveryDate()
                    + "，早于系统可行交期 " + row.computedDeliveryDate() + "（相差 "
                    + Math.abs(row.varianceDays() == null ? 0 : row.varianceDays()) + " 天），请与医生确认。";
            case "WAITING_DOCTOR_CONFIRMATION" -> "有过程确认环节超过宽限期未获医生确认，订单处于等待状态。";
            case "LATER_THAN_PLAN" -> "医生要求到货 " + row.doctorRequestedDeliveryDate()
                    + "，晚于系统可行交期 " + row.computedDeliveryDate() + "。";
            default -> null;
        };
    }

    private JsonNode internalFormData(OrderReadRow row) {
        JsonNode parsed = readJson(row.formData());
        if (!(parsed instanceof ObjectNode)) {
            return parsed;
        }
        ObjectNode result = ((ObjectNode) parsed).deepCopy();

        if (!result.hasNonNull("patient_name") && row.patientName() != null && !row.patientName().isBlank()) {
            result.put("patient_name", row.patientName());
        }

        JsonNode normalizedValues = readJson(row.normalizedFormValues());
        copyCompatibilityValue(result, "tooth_position", normalizedValues, "tooth_position", "tooth_positions");
        copyCompatibilityValue(result, "doctor_note", normalizedValues, "doctor_note", "case_note");

        JsonNode productSnapshot = readJson(row.catalogProductSnapshot());
        if (!result.hasNonNull("material") && productSnapshot.path("materials").isArray()) {
            List<String> materials = new ArrayList<>();
            for (JsonNode material : productSnapshot.path("materials")) {
                String name = material.path("display_name").asText("").trim();
                int quantity = material.path("quantity").asInt(1);
                if (!name.isBlank()) {
                    materials.add(quantity > 1 ? name + " × " + quantity : name);
                }
            }
            if (!materials.isEmpty()) {
                result.put("material", String.join("、", materials));
            }
        }
        if (!result.hasNonNull("accessories") && productSnapshot.path("accessories").isArray()) {
            List<String> accessories = new ArrayList<>();
            for (JsonNode accessory : productSnapshot.path("accessories")) {
                String name = accessory.path("display_name").asText("").trim();
                int quantity = accessory.path("quantity").asInt(1);
                if (!name.isBlank()) {
                    accessories.add(quantity > 1 ? name + " × " + quantity : name);
                }
            }
            if (!accessories.isEmpty()) {
                result.put("accessories", String.join("、", accessories));
            }
        }
        return result;
    }

    private void copyCompatibilityValue(
            ObjectNode target, String targetKey, JsonNode source, String... sourceKeys) {
        if (target.hasNonNull(targetKey) || source == null || !source.isObject()) {
            return;
        }
        for (String sourceKey : sourceKeys) {
            JsonNode value = source.get(sourceKey);
            if (value != null && !value.isNull()
                    && !(value.isTextual() && value.asText().isBlank())
                    && !(value.isArray() && value.isEmpty())) {
                target.set(targetKey, value.deepCopy());
                return;
            }
        }
    }

    private boolean orderExists(long orderId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM orders WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single() > 0;
    }

    private String doctorVisibleMessageSummary(long orderId) {
        return jdbcClient.sql("""
                        SELECT GROUP_CONCAT(content ORDER BY created_at SEPARATOR '；')
                        FROM order_message
                        WHERE order_id = :orderId
                          AND visibility IN ('DOCTOR', 'DOCTOR_CS', 'ALL')
                          AND review_status IN ('DIRECT', 'APPROVED')
                        """)
                .param("orderId", orderId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "invalid stored order json", ex);
        }
    }

    private record DoctorProgressMilestone(
            String key, String label, String externalStatus, int rank, String activeNote) {
    }

    private record DoctorStatusOccurrence(String externalStatus, LocalDateTime occurredAt) {
    }

    private record OrderReadRow(
            long orderId,
            String orderNo,
            Long groupId,
            long clinicId,
            String clinicName,
            Long doctorUserId,
            String doctorName,
            Long patientId,
            String patientName,
            Long csUserId,
            String productType,
            String internalStatus,
            String externalStatus,
            String productionNote,
            String rejectReason,
            String formData,
            String catalogProductSnapshot,
            String formSchemaSnapshot,
            String normalizedFormValues,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            String publicMessage,
            String billStatus,
            String logisticsStatus,
            String trackingNo,
            LocalDate computedDeliveryDate,
            LocalDate doctorRequestedDeliveryDate,
            Integer varianceDays,
            String varianceFlag,
            int waitingDays,
            String deliveryEstimateStatus,
            boolean confirmationOverdue) {
    }

    public record OrderListResponse<T>(List<T> items, long total, int page, int size) {
    }
}
