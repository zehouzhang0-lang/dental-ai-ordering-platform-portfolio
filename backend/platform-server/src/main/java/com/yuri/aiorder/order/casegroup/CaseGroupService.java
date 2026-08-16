package com.yuri.aiorder.order.casegroup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.BusinessTime;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.AccessControlService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CaseGroupService {

    private static final List<String> EXTERNAL_STATUS_ORDER = List.of(
            "PENDING_REVIEW",
            "DESIGNING",
            "PRODUCING",
            "QC",
            "PENDING_SHIP",
            "SHIPPED",
            "COMPLETED");

    private final JdbcClient jdbcClient;
    private final AccessControlService accessControlService;
    private final ObjectMapper objectMapper;

    public CaseGroupService(
            JdbcClient jdbcClient,
            AccessControlService accessControlService,
            ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.accessControlService = accessControlService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CaseGroupResponse createDraft(CreateCaseGroupRequest request, BootstrapIdentity identity) {
        accessControlService.requireDoctorPortalAction(
                identity, "order:write-doctor", "only doctors can create case groups");
        accessControlService.requireScopedIdentity(identity, "CLINIC");
        ensureClinicCanOrder(identity.clinicId());
        requireOwnedPatient(request.patientId(), identity);

        String idempotencyKey = request.idempotencyKey().trim();
        CaseGroupRow existing = findByIdempotencyKey(identity.userId(), idempotencyKey);
        if (existing != null) {
            ensureIdempotentRequestMatches(existing, request.patientId(), identity.clinicId());
            return toResponse(existing, List.of());
        }

        String groupNo = nextGroupNo();
        try {
            jdbcClient.sql("""
                            INSERT INTO order_case_group
                                (group_no, clinic_id, doctor_user_id, patient_id,
                                 lifecycle_status, idempotency_key)
                            VALUES
                                (:groupNo, :clinicId, :doctorUserId, :patientId,
                                 'DRAFT', :idempotencyKey)
                            """)
                    .param("groupNo", groupNo)
                    .param("clinicId", identity.clinicId())
                    .param("doctorUserId", identity.userId())
                    .param("patientId", request.patientId())
                    .param("idempotencyKey", idempotencyKey)
                    .update();
        } catch (DuplicateKeyException duplicate) {
            CaseGroupRow concurrent = findByIdempotencyKey(identity.userId(), idempotencyKey);
            if (concurrent == null) {
                throw duplicate;
            }
            ensureIdempotentRequestMatches(concurrent, request.patientId(), identity.clinicId());
            return toResponse(concurrent, List.of());
        }
        long groupId = jdbcClient.sql("""
                        SELECT group_id
                        FROM order_case_group
                        WHERE group_no = :groupNo
                        """)
                .param("groupNo", groupNo)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO order_case_group_audit
                            (group_id, action_type, after_value,
                             operator_user_id, idempotency_key)
                        VALUES
                            (:groupId, 'CREATE_GROUP',
                             JSON_OBJECT('group_no', :groupNo, 'patient_id', :patientId),
                             :userId, :idempotencyKey)
                        """)
                .param("groupId", groupId)
                .param("groupNo", groupNo)
                .param("patientId", request.patientId())
                .param("userId", identity.userId())
                .param("idempotencyKey", idempotencyKey)
                .update();
        return getByGroupNo(groupNo, identity);
    }

    @Transactional(readOnly = true)
    public CaseGroupResponse get(long groupId, BootstrapIdentity identity) {
        CaseGroupRow group = loadGroup(groupId);
        requireReadScope(group, identity);
        return toResponse(group, loadItems(groupId));
    }

    private CaseGroupResponse getByGroupNo(String groupNo, BootstrapIdentity identity) {
        CaseGroupRow group;
        try {
            group = jdbcClient.sql("""
                            SELECT group_id, group_no, clinic_id, doctor_user_id, patient_id,
                                   lifecycle_status, draft_version, idempotency_key,
                                   created_at, updated_at
                            FROM order_case_group
                            WHERE group_no = :groupNo
                            """)
                    .param("groupNo", groupNo)
                    .query(CaseGroupService::mapGroup)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "case group not found", ex);
        }
        requireReadScope(group, identity);
        return toResponse(group, loadItems(group.groupId()));
    }

    private CaseGroupRow loadGroup(long groupId) {
        try {
            return jdbcClient.sql("""
                            SELECT group_id, group_no, clinic_id, doctor_user_id, patient_id,
                                   lifecycle_status, draft_version, idempotency_key,
                                   created_at, updated_at
                            FROM order_case_group
                            WHERE group_id = :groupId
                            """)
                    .param("groupId", groupId)
                    .query(CaseGroupService::mapGroup)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "case group not found", ex);
        }
    }

    private CaseGroupRow findByIdempotencyKey(Long doctorUserId, String idempotencyKey) {
        return jdbcClient.sql("""
                        SELECT group_id, group_no, clinic_id, doctor_user_id, patient_id,
                               lifecycle_status, draft_version, idempotency_key,
                               created_at, updated_at
                        FROM order_case_group
                        WHERE doctor_user_id = :doctorUserId
                          AND idempotency_key = :idempotencyKey
                        """)
                .param("doctorUserId", doctorUserId)
                .param("idempotencyKey", idempotencyKey)
                .query(CaseGroupService::mapGroup)
                .optional()
                .orElse(null);
    }

    private List<CaseGroupResponse.CaseGroupItemResponse> loadItems(long groupId) {
        return jdbcClient.sql("""
                        SELECT orders.order_id, orders.order_no, orders.line_no,
                               orders.relationship_type, orders.item_client_key,
                               orders.product_id, product.product_code,
                               product.display_name AS product_name,
                               orders.variant_id, variant.variant_code,
                               variant.display_name AS variant_name,
                               orders.product_type, product.pricing_status,
                               orders.quoted_price_cents, orders.quoted_price_currency,
                               orders.form_data, orders.external_status, orders.created_at,
                               snapshot.snapshot_id
                        FROM orders
                        LEFT JOIN catalog_product_v2 product
                          ON product.product_id = orders.product_id
                        LEFT JOIN catalog_product_variant_v2 variant
                          ON variant.variant_id = orders.variant_id
                        LEFT JOIN order_catalog_snapshot snapshot
                          ON snapshot.order_id = orders.order_id
                        WHERE orders.group_id = :groupId
                        ORDER BY orders.line_no, orders.order_id
                        """)
                .param("groupId", groupId)
                .query((rs, rowNum) -> {
                    JsonNode root = readJson(rs.getString("form_data"));
                    JsonNode formValues = root.has("form_values") ? root.path("form_values") : root;
                    JsonNode materials = root.has("material_selections")
                            ? root.path("material_selections")
                            : objectMapper.createArrayNode();
                    JsonNode accessories = root.has("accessory_selections")
                            ? root.path("accessory_selections")
                            : objectMapper.createArrayNode();
                    Long quotedPrice = rs.getObject("quoted_price_cents", Long.class);
                    String pricingStatus = rs.getString("pricing_status");
                    if (pricingStatus == null) {
                        pricingStatus = quotedPrice == null ? "PENDING_QUOTE" : "PRICED";
                    }
                    return new CaseGroupResponse.CaseGroupItemResponse(
                            rs.getLong("order_id"),
                            rs.getString("order_no"),
                            rs.getInt("line_no"),
                            rs.getString("relationship_type"),
                            rs.getString("item_client_key"),
                            rs.getObject("product_id", Long.class),
                            rs.getString("product_code"),
                            rs.getString("product_name"),
                            rs.getObject("variant_id", Long.class),
                            rs.getString("variant_code"),
                            rs.getString("variant_name"),
                            rs.getString("product_type"),
                            pricingStatus,
                            quotedPrice,
                            rs.getString("quoted_price_currency"),
                            rs.getObject("snapshot_id") == null ? "DRAFT" : "FROZEN",
                            formValues,
                            materials,
                            accessories,
                            loadActiveFileIds(rs.getLong("order_id"), "ORDER"),
                            rs.getString("external_status"),
                            rs.getObject("created_at", LocalDateTime.class));
                })
                .list();
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value == null ? "{}" : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("stored order form data is invalid", ex);
        }
    }

    private void requireOwnedPatient(Long patientId, BootstrapIdentity identity) {
        if (patientId == null) {
            return;
        }
        long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM patient_record
                        WHERE patient_id = :patientId
                          AND clinic_id = :clinicId
                          AND doctor_user_id = :doctorUserId
                          AND status = 'ACTIVE'
                        """)
                .param("patientId", patientId)
                .param("clinicId", identity.clinicId())
                .param("doctorUserId", identity.userId())
                .query(Long.class)
                .single();
        if (count == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "doctor cannot use this patient");
        }
    }

    private void ensureClinicCanOrder(Long clinicId) {
        long available = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM clinic
                        WHERE clinic_id = :clinicId
                          AND status = 'ACTIVE'
                          AND NOT EXISTS (
                              SELECT 1
                              FROM clinic_blacklist_record blacklist
                              WHERE blacklist.clinic_id = clinic.clinic_id
                                AND blacklist.blacklist_status = 'ACTIVE'
                          )
                        """)
                .param("clinicId", clinicId)
                .query(Long.class)
                .single();
        if (available == 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "clinic is inactive or restricted from ordering");
        }
    }

    private void requireReadScope(CaseGroupRow group, BootstrapIdentity identity) {
        if (identity.role() == UserRole.DOCTOR) {
            if (!Objects.equals(identity.userId(), group.doctorUserId())
                    || !Objects.equals(identity.clinicId(), group.clinicId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "doctor cannot access this case group");
            }
            return;
        }
        accessControlService.requirePermission(
                identity,
                "order:read-case-group-internal",
                "case group requires doctor ownership or internal order access");
    }

    private void ensureIdempotentRequestMatches(
            CaseGroupRow existing, Long patientId, Long clinicId) {
        if (!Objects.equals(existing.patientId(), patientId)
                || !Objects.equals(existing.clinicId(), clinicId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "idempotency key is already used by a different case group request");
        }
    }

    private CaseGroupResponse toResponse(
            CaseGroupRow group,
            List<CaseGroupResponse.CaseGroupItemResponse> items) {
        return new CaseGroupResponse(
                group.groupId(),
                group.groupNo(),
                group.clinicId(),
                group.doctorUserId(),
                group.patientId(),
                group.lifecycleStatus(),
                deriveExternalStatus(group.lifecycleStatus(), items),
                group.draftVersion(),
                loadActiveSharedFileIds(group.groupId()),
                items,
                group.createdAt(),
                group.updatedAt());
    }

    private List<Long> loadActiveFileIds(long orderId, String attachmentScope) {
        return jdbcClient.sql("""
                        SELECT file_id
                        FROM file_resource
                        WHERE order_id = :orderId
                          AND attachment_scope = :attachmentScope
                          AND status = 'ACTIVE'
                        ORDER BY file_id
                        """)
                .param("orderId", orderId)
                .param("attachmentScope", attachmentScope)
                .query(Long.class)
                .list();
    }

    private List<Long> loadActiveSharedFileIds(long groupId) {
        return jdbcClient.sql("""
                        SELECT file_id
                        FROM file_resource
                        WHERE case_group_id = :groupId
                          AND attachment_scope = 'SHARED'
                          AND status = 'ACTIVE'
                        ORDER BY file_id
                        """)
                .param("groupId", groupId)
                .query(Long.class)
                .list();
    }

    private String deriveExternalStatus(
            String lifecycleStatus,
            List<CaseGroupResponse.CaseGroupItemResponse> items) {
        if ("CANCELLED".equals(lifecycleStatus)) {
            return "CANCELLED";
        }
        if (items.isEmpty()) {
            return "DRAFT";
        }
        Set<String> statuses = items.stream()
                .map(CaseGroupResponse.CaseGroupItemResponse::externalStatus)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (statuses.size() == 1) {
            return statuses.iterator().next();
        }
        long completedCount = items.stream()
                .filter(item -> "COMPLETED".equals(item.externalStatus()))
                .count();
        if (completedCount > 0) {
            return "PARTIALLY_COMPLETED";
        }
        return statuses.stream()
                .min(Comparator.comparingInt(status -> {
                    int index = EXTERNAL_STATUS_ORDER.indexOf(status.toUpperCase(Locale.ROOT));
                    return index < 0 ? Integer.MAX_VALUE : index;
                }))
                .orElse("IN_PROGRESS");
    }

    private String nextGroupNo() {
        String date = BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 10)
                .toUpperCase(Locale.ROOT);
        return "CASE" + date + "-" + suffix;
    }

    private static CaseGroupRow mapGroup(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new CaseGroupRow(
                rs.getLong("group_id"),
                rs.getString("group_no"),
                rs.getLong("clinic_id"),
                rs.getObject("doctor_user_id", Long.class),
                rs.getObject("patient_id", Long.class),
                rs.getString("lifecycle_status"),
                rs.getInt("draft_version"),
                rs.getString("idempotency_key"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private record CaseGroupRow(
            long groupId,
            String groupNo,
            Long clinicId,
            Long doctorUserId,
            Long patientId,
            String lifecycleStatus,
            int draftVersion,
            String idempotencyKey,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }
}
