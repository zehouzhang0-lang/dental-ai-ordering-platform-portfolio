package com.yuri.aiorder.quality;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.AccessControlService;
import com.yuri.aiorder.order.api.OrderProjectionQueryService.OrderListResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class QualityRecordService {

    private static final String EXTERNAL_RETURN = "EXTERNAL_RETURN";
    private static final String REASON_CATEGORY = "REASON_CATEGORY";
    private static final String RESPONSIBILITY_TYPE = "RESPONSIBILITY_TYPE";
    private static final Set<String> QUALITY_RECORD_STATUSES = Set.of("PENDING", "IN_PROGRESS", "RESOLVED", "CLOSED");

    private final JdbcClient jdbcClient;
    private final AccessControlService accessControlService;

    public QualityRecordService(JdbcClient jdbcClient, AccessControlService accessControlService) {
        this.jdbcClient = jdbcClient;
        this.accessControlService = accessControlService;
    }

    public OrderListResponse<QualityRecordResponse> listQualityRecords(
            BootstrapIdentity identity,
            String recordType,
            String status,
            String responsibilityType,
            Long orderId,
            int page,
            int size) {
        accessControlService.requirePermission(
                identity, "check:read-internal", "quality records are internal only");
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(size, 100));
        int offset = (safePage - 1) * safeSize;
        String normalizedRecordType = normalizeOptional(recordType);
        if (normalizedRecordType != null && !EXTERNAL_RETURN.equals(normalizedRecordType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported quality record type");
        }
        String normalizedStatus = normalizeOptional(status);
        String normalizedResponsibilityType = normalizeOptional(responsibilityType);
        if (normalizedResponsibilityType != null) {
            requireActiveDictionaryValue(normalizedResponsibilityType, RESPONSIBILITY_TYPE, "unsupported responsibility_type");
        }

        List<String> filters = new ArrayList<>();
        if (normalizedRecordType != null) {
            filters.add("qr.record_type = :recordType");
        }
        if (normalizedStatus != null) {
            filters.add("qr.status = :status");
        }
        if (normalizedResponsibilityType != null) {
            filters.add("qr.responsibility_type = :responsibilityType");
        }
        if (orderId != null) {
            filters.add("qr.order_id = :orderId");
        }
        String whereClause = filters.isEmpty() ? "" : " WHERE " + String.join(" AND ", filters) + " ";

        JdbcClient.StatementSpec dataSpec = bindFilters(jdbcClient.sql("""
                        SELECT
                            qr.quality_record_id,
                            qr.record_type,
                            qr.check_result,
                            qr.status,
                            qr.status_note,
                            qr.created_at,
                            qr.status_updated_at,
                            qr.updated_at,
                            o.order_id,
                            o.order_no,
                            o.product_type,
                            cl.clinic_name,
                            c.check_id,
                            r.rework_id,
                            qr.reason_category,
                            qr.reason_detail,
                            qr.responsibility_type
                        FROM quality_record qr
                        JOIN orders o ON o.order_id = qr.order_id
                        JOIN clinic cl ON cl.clinic_id = o.clinic_id
                        LEFT JOIN check_record c ON c.check_id = qr.source_check_id
                        LEFT JOIN rework_record r ON r.rework_id = qr.rework_id
                        """ + whereClause + """
                        ORDER BY qr.created_at DESC, qr.quality_record_id DESC
                        LIMIT :limit OFFSET :offset
                        """), normalizedRecordType, normalizedStatus, normalizedResponsibilityType, orderId)
                .param("limit", safeSize)
                .param("offset", offset);

        List<QualityRecordResponse> items = dataSpec.query((rs, rowNum) -> new QualityRecordResponse(
                        rs.getLong("quality_record_id"),
                        rs.getString("record_type"),
                        rs.getLong("order_id"),
                        rs.getString("order_no"),
                        rs.getString("product_type"),
                        rs.getString("clinic_name"),
                        rs.getObject("check_id", Long.class) == null ? 0L : rs.getLong("check_id"),
                        rs.getString("check_result"),
                        rs.getObject("rework_id", Long.class),
                        rs.getString("reason_category"),
                        rs.getString("reason_detail"),
                        rs.getString("responsibility_type"),
                        rs.getString("status"),
                        rs.getString("status_note"),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("status_updated_at", LocalDateTime.class),
                        rs.getObject("updated_at", LocalDateTime.class)))
                .list();

        long total = bindFilters(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM quality_record qr
                        """ + whereClause), normalizedRecordType, normalizedStatus, normalizedResponsibilityType, orderId)
                .query(Long.class)
                .single();
        return new OrderListResponse<>(items, total, safePage, safeSize);
    }

    @Transactional
    public QualityRecordResponse createExternalReturn(
            BootstrapIdentity identity, ExternalReturnQualityRecordRequest request) {
        accessControlService.requirePermission(
                identity, "quality:external-return:manage", "external return registration requires quality:external-return:manage");
        if (request.orderId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "order_id is required");
        }
        String reasonCategory = normalizeRequired(request.reasonCategory(), "reason_category");
        String responsibilityType = normalizeRequired(request.responsibilityType(), "responsibility_type");
        String reasonDetail = normalizeRequired(request.reasonDetail(), "reason_detail");
        requireActiveDictionaryValue(reasonCategory, REASON_CATEGORY, "unsupported reason_category");
        requireActiveDictionaryValue(responsibilityType, RESPONSIBILITY_TYPE, "unsupported responsibility_type");
        requireOrder(request.orderId());

        jdbcClient.sql("""
                        INSERT INTO check_record
                            (order_id, node_instance_id, check_type, result, checker_user_id, note)
                        VALUES
                            (:orderId, NULL, :checkType, 'FAIL', :checkerUserId, :note)
                        """)
                .param("orderId", request.orderId())
                .param("checkType", EXTERNAL_RETURN)
                .param("checkerUserId", identity.userId())
                .param("note", reasonDetail)
                .update();
        long checkId = lastInsertId();

        jdbcClient.sql("""
                        INSERT INTO rework_record
                            (order_id, source_check_id, reason_category, reason_detail, responsibility_type, status)
                        VALUES
                            (:orderId, :sourceCheckId, :reasonCategory, :reasonDetail, :responsibilityType, 'PENDING')
                        """)
                .param("orderId", request.orderId())
                .param("sourceCheckId", checkId)
                .param("reasonCategory", reasonCategory)
                .param("reasonDetail", reasonDetail)
                .param("responsibilityType", responsibilityType)
                .update();
        long reworkId = lastInsertId();

        jdbcClient.sql("""
                        INSERT INTO quality_record
                            (order_id, record_type, source_check_id, rework_id, check_result, reason_category,
                             reason_detail, responsibility_type, status, created_by_user_id)
                        VALUES
                            (:orderId, :recordType, :sourceCheckId, :reworkId, 'FAIL', :reasonCategory,
                             :reasonDetail, :responsibilityType, 'PENDING', :createdByUserId)
                        """)
                .param("orderId", request.orderId())
                .param("recordType", EXTERNAL_RETURN)
                .param("sourceCheckId", checkId)
                .param("reworkId", reworkId)
                .param("reasonCategory", reasonCategory)
                .param("reasonDetail", reasonDetail)
                .param("responsibilityType", responsibilityType)
                .param("createdByUserId", identity.userId())
                .update();
        return requireQualityRecord(lastInsertId());
    }

    @Transactional
    public QualityRecordResponse updateStatus(
            BootstrapIdentity identity, long qualityRecordId, QualityRecordStatusUpdateRequest request) {
        accessControlService.requirePermission(
                identity, "quality:record:manage", "quality record status requires quality:record:manage");
        String status = normalizeRequired(request.status(), "status");
        if (!QUALITY_RECORD_STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported quality record status");
        }
        String statusNote = normalizeOptionalText(request.statusNote());
        int updated = jdbcClient.sql("""
                        UPDATE quality_record
                        SET status = :status,
                            status_note = :statusNote,
                            status_updated_by_user_id = :statusUpdatedByUserId,
                            status_updated_at = CURRENT_TIMESTAMP(3)
                        WHERE quality_record_id = :qualityRecordId
                        """)
                .param("status", status)
                .param("statusNote", statusNote)
                .param("statusUpdatedByUserId", identity.userId())
                .param("qualityRecordId", qualityRecordId)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "quality record not found");
        }
        return requireQualityRecord(qualityRecordId);
    }

    private QualityRecordResponse requireQualityRecord(long qualityRecordId) {
        try {
            return jdbcClient.sql("""
                            SELECT
                                qr.quality_record_id,
                                qr.record_type,
                                qr.check_result,
                                qr.status,
                                qr.status_note,
                                qr.created_at,
                                qr.status_updated_at,
                                qr.updated_at,
                                c.check_id,
                                o.order_id,
                                o.order_no,
                                o.product_type,
                                cl.clinic_name,
                                r.rework_id,
                                qr.reason_category,
                                qr.reason_detail,
                                qr.responsibility_type
                            FROM quality_record qr
                            JOIN orders o ON o.order_id = qr.order_id
                            JOIN clinic cl ON cl.clinic_id = o.clinic_id
                            LEFT JOIN check_record c ON c.check_id = qr.source_check_id
                            LEFT JOIN rework_record r ON r.rework_id = qr.rework_id
                            WHERE qr.quality_record_id = :qualityRecordId
                            """)
                    .param("qualityRecordId", qualityRecordId)
                    .query((rs, rowNum) -> new QualityRecordResponse(
                            rs.getLong("quality_record_id"),
                            rs.getString("record_type"),
                            rs.getLong("order_id"),
                            rs.getString("order_no"),
                            rs.getString("product_type"),
                            rs.getString("clinic_name"),
                            rs.getObject("check_id", Long.class) == null ? 0L : rs.getLong("check_id"),
                            rs.getString("check_result"),
                            rs.getObject("rework_id", Long.class),
                            rs.getString("reason_category"),
                            rs.getString("reason_detail"),
                            rs.getString("responsibility_type"),
                            rs.getString("status"),
                            rs.getString("status_note"),
                            rs.getObject("created_at", LocalDateTime.class),
                            rs.getObject("status_updated_at", LocalDateTime.class),
                            rs.getObject("updated_at", LocalDateTime.class)))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "quality record not found", ex);
        }
    }

    private JdbcClient.StatementSpec bindFilters(
            JdbcClient.StatementSpec spec,
            String recordType,
            String status,
            String responsibilityType,
            Long orderId) {
        if (recordType != null) {
            spec = spec.param("recordType", recordType);
        }
        if (status != null) {
            spec = spec.param("status", status);
        }
        if (responsibilityType != null) {
            spec = spec.param("responsibilityType", responsibilityType);
        }
        if (orderId != null) {
            spec = spec.param("orderId", orderId);
        }
        return spec;
    }

    private void requireOrder(long orderId) {
        boolean exists = jdbcClient.sql("SELECT COUNT(*) FROM orders WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single() > 0;
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found");
        }
    }

    private void requireActiveDictionaryValue(String value, String dictionaryType, String message) {
        boolean exists = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM rework_dictionary_item
                        WHERE dictionary_type = :dictionaryType
                          AND item_code = :itemCode
                          AND status = 'ACTIVE'
                        """)
                .param("dictionaryType", dictionaryType)
                .param("itemCode", value)
                .query(Long.class)
                .single() > 0;
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private String normalizeRequired(String value, String fieldName) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
        return normalized;
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private long lastInsertId() {
        return jdbcClient.sql("SELECT LAST_INSERT_ID()")
                .query(Long.class)
                .single();
    }
}
