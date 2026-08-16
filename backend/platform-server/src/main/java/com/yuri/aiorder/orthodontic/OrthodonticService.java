package com.yuri.aiorder.orthodontic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.orthodontic.OrthodonticModels.CreateChangeRequest;
import com.yuri.aiorder.orthodontic.OrthodonticModels.CreatePlanVersionRequest;
import com.yuri.aiorder.orthodontic.OrthodonticModels.CreateProductionBatchRequest;
import com.yuri.aiorder.orthodontic.OrthodonticModels.ReviewPlanRequest;
import com.yuri.aiorder.orthodontic.OrthodonticModels.SavePrescriptionRequest;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrthodonticService {

    private static final Set<String> ORTHODONTIC_PRODUCT_TYPES = Set.of(
            "ORTHODONTICS", "ORTHODONTIC", "CLEAR_ALIGNER");

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public OrthodonticService(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(long orderId, BootstrapIdentity identity) {
        OrderScope order = requireOrder(orderId);
        requireRead(order, identity);
        Long caseId = findCaseId(orderId);
        if (caseId == null) {
            return Map.of(
                    "order_id", orderId,
                    "configured", false,
                    "required_sections", List.of(
                            "basic_information", "records_and_models", "clinical_diagnosis",
                            "appliance_and_combination", "tooth_targets", "plan_parameters",
                            "preview_and_submission"));
        }
        Map<String, Object> response = caseResponse(caseId);
        return identity.role() == UserRole.DOCTOR ? doctorCaseResponse(response) : response;
    }

    @Transactional
    public Map<String, Object> savePrescription(
            long orderId,
            SavePrescriptionRequest request,
            BootstrapIdentity identity) {
        OrderScope order = requireOrder(orderId);
        requireDoctorOwner(order, identity);
        requireOrthodontic(order);
        requireConfiguredAlignerType(orderId, request.alignerTypeCode());
        if (!Set.of("DRAFT", "CS_REJECTED", "PRODUCTION_REJECTED").contains(order.internalStatus())) {
            throw conflict("orthodontic prescription can only be edited before order acceptance");
        }
        requireSectionObject(request.basicInformation(), "basic_information");
        requireSectionObject(request.recordsAndModels(), "records_and_models");
        requireSectionObject(request.clinicalDiagnosis(), "clinical_diagnosis");
        requireSectionObject(request.applianceAndCombination(), "appliance_and_combination");
        requireSectionObject(request.toothTargets(), "tooth_targets");
        requireSectionObject(request.planParameters(), "plan_parameters");
        requireSectionObject(request.previewAndSubmission(), "preview_and_submission");
        requireCombinedOrder(order, request.combinedOrderId());

        ObjectNode prescription = objectMapper.createObjectNode();
        prescription.set("basic_information", request.basicInformation());
        prescription.set("records_and_models", request.recordsAndModels());
        prescription.set("clinical_diagnosis", request.clinicalDiagnosis());
        prescription.set("appliance_and_combination", request.applianceAndCombination());
        prescription.set("tooth_targets", request.toothTargets());
        prescription.set("plan_parameters", request.planParameters());
        prescription.set("preview_and_submission", request.previewAndSubmission());
        String nextStatus = Boolean.TRUE.equals(request.submit()) ? "PRESCRIPTION_SUBMITTED" : "DRAFT";
        Long existingCaseId = findCaseId(orderId);
        if (existingCaseId == null) {
            if (request.expectedLockVersion() != null && request.expectedLockVersion() != 0) {
                throw conflict("orthodontic case changed concurrently; refresh and retry");
            }
            jdbcClient.sql("""
                            INSERT INTO orthodontic_case
                                (order_id, aligner_type_code, combined_order_id, case_status,
                                 prescription_json, total_steps, created_by_user_id)
                            VALUES
                                (:orderId, :alignerType, :combinedOrderId, :caseStatus,
                                 CAST(:prescription AS JSON), :totalSteps, :userId)
                            """)
                    .param("orderId", orderId)
                    .param("alignerType", request.alignerTypeCode())
                    .param("combinedOrderId", request.combinedOrderId())
                    .param("caseStatus", nextStatus)
                    .param("prescription", json(prescription))
                    .param("totalSteps", request.totalSteps())
                    .param("userId", identity.userId())
                    .update();
            long caseId = Objects.requireNonNull(findCaseId(orderId));
            audit(caseId, "ORTHODONTIC_CASE", caseId, "CREATE_PRESCRIPTION", null, caseResponse(caseId), identity, null);
            return doctorCaseResponse(caseResponse(caseId));
        }

        Map<String, Object> before = caseResponse(existingCaseId);
        int expected = Objects.requireNonNullElse(request.expectedLockVersion(), -1);
        int updated = jdbcClient.sql("""
                        UPDATE orthodontic_case
                        SET aligner_type_code = :alignerType,
                            combined_order_id = :combinedOrderId,
                            case_status = :caseStatus,
                            prescription_json = CAST(:prescription AS JSON),
                            total_steps = :totalSteps,
                            prescription_version = prescription_version + 1,
                            lock_version = lock_version + 1
                        WHERE orthodontic_case_id = :caseId
                          AND lock_version = :expected
                        """)
                .param("alignerType", request.alignerTypeCode())
                .param("combinedOrderId", request.combinedOrderId())
                .param("caseStatus", nextStatus)
                .param("prescription", json(prescription))
                .param("totalSteps", request.totalSteps())
                .param("caseId", existingCaseId)
                .param("expected", expected)
                .update();
        if (updated == 0) {
            throw conflict("orthodontic case changed concurrently; refresh and retry");
        }
        Map<String, Object> after = caseResponse(existingCaseId);
        audit(existingCaseId, "ORTHODONTIC_CASE", existingCaseId, "UPDATE_PRESCRIPTION", before, after, identity, null);
        return doctorCaseResponse(after);
    }

    @Transactional
    public Map<String, Object> createPlanVersion(
            long orderId,
            CreatePlanVersionRequest request,
            BootstrapIdentity identity) {
        OrderScope order = requireOrder(orderId);
        requireInternalRead(order, identity);
        Long caseId = requireCaseId(orderId);
        String status = caseStatus(caseId);
        if (!Set.of("PRESCRIPTION_SUBMITTED", "PLAN_DESIGN", "INTERNAL_REVIEW", "DOCTOR_REVIEW")
                .contains(status)) {
            throw conflict("orthodontic prescription must be submitted before plan design");
        }
        if (!request.planSnapshot().isObject()) {
            throw badRequest("plan_snapshot must be an object");
        }
        validatePlanFile(request.planFileId(), identity);
        int versionNo = jdbcClient.sql("""
                        SELECT COALESCE(MAX(version_no), 0) + 1
                        FROM orthodontic_plan_version
                        WHERE orthodontic_case_id = :caseId
                        FOR UPDATE
                        """)
                .param("caseId", caseId)
                .query(Integer.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO orthodontic_plan_version
                            (orthodontic_case_id, version_no, plan_status, plan_file_id,
                             plan_snapshot_json, design_note, created_by_user_id)
                        VALUES
                            (:caseId, :versionNo, 'PENDING_INTERNAL_REVIEW', :fileId,
                             CAST(:snapshot AS JSON), :note, :userId)
                        """)
                .param("caseId", caseId)
                .param("versionNo", versionNo)
                .param("fileId", request.planFileId())
                .param("snapshot", json(request.planSnapshot()))
                .param("note", request.designNote())
                .param("userId", identity.userId())
                .update();
        long planId = jdbcClient.sql("""
                        SELECT plan_version_id
                        FROM orthodontic_plan_version
                        WHERE orthodontic_case_id = :caseId AND version_no = :versionNo
                        """)
                .param("caseId", caseId)
                .param("versionNo", versionNo)
                .query(Long.class)
                .single();
        updateCaseStatus(caseId, "INTERNAL_REVIEW");
        audit(caseId, "PLAN_VERSION", planId, "CREATE_PLAN_VERSION", null, planSnapshot(planId), identity, null);
        return caseResponse(caseId);
    }

    @Transactional
    public Map<String, Object> reviewInternal(
            long planVersionId,
            ReviewPlanRequest request,
            BootstrapIdentity identity) {
        requirePortalPermission(
                identity,
                "design-draft:internal-review",
                Set.of(UserRole.WORKER));
        PlanScope plan = requirePlan(planVersionId);
        requireInternalRead(requireOrder(plan.orderId()), identity);
        if (!"PENDING_INTERNAL_REVIEW".equals(plan.status())) {
            throw conflict("plan is not waiting for internal review");
        }
        requireRejectReason(request);
        String nextPlan = "APPROVE".equals(request.decision())
                ? "PENDING_DOCTOR_REVIEW"
                : "INTERNAL_REJECTED";
        String nextCase = "APPROVE".equals(request.decision()) ? "DOCTOR_REVIEW" : "PLAN_DESIGN";
        transitionPlan(planVersionId, plan.status(), nextPlan);
        recordReview(planVersionId, "INTERNAL", request, identity);
        updateCaseStatus(plan.caseId(), nextCase);
        audit(plan.caseId(), "PLAN_VERSION", planVersionId, "INTERNAL_" + request.decision(), null, planSnapshot(planVersionId), identity, request.reason());
        return caseResponse(plan.caseId());
    }

    @Transactional
    public Map<String, Object> reviewDoctor(
            long planVersionId,
            ReviewPlanRequest request,
            BootstrapIdentity identity) {
        PlanScope plan = requirePlan(planVersionId);
        OrderScope order = requireOrder(plan.orderId());
        requireDoctorOwner(order, identity);
        if (!"PENDING_DOCTOR_REVIEW".equals(plan.status())) {
            throw conflict("plan is not waiting for doctor review");
        }
        requireRejectReason(request);
        String nextPlan = "APPROVE".equals(request.decision())
                ? "DOCTOR_APPROVED"
                : "DOCTOR_REJECTED";
        String nextCase = "APPROVE".equals(request.decision()) ? "PLAN_CONFIRMED" : "PLAN_DESIGN";
        transitionPlan(planVersionId, plan.status(), nextPlan);
        recordReview(planVersionId, "DOCTOR", request, identity);
        updateCaseStatus(plan.caseId(), nextCase);
        audit(plan.caseId(), "PLAN_VERSION", planVersionId, "DOCTOR_" + request.decision(), null, planSnapshot(planVersionId), identity, request.reason());
        return doctorCaseResponse(caseResponse(plan.caseId()));
    }

    @Transactional
    public Map<String, Object> createBatch(
            long orderId,
            CreateProductionBatchRequest request,
            BootstrapIdentity identity) {
        OrderScope order = requireOrder(orderId);
        requireInternalRead(order, identity);
        long caseId = requireCaseId(orderId);
        PlanScope plan = requirePlan(request.planVersionId());
        if (plan.caseId() != caseId || !"DOCTOR_APPROVED".equals(plan.status())) {
            throw conflict("production batch requires the doctor-approved plan for this case");
        }
        Integer totalSteps = jdbcClient.sql("""
                        SELECT total_steps FROM orthodontic_case WHERE orthodontic_case_id = :caseId
                        """)
                .param("caseId", caseId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        if (request.stepTo() < request.stepFrom()
                || (totalSteps != null && request.stepTo() > totalSteps)) {
            throw badRequest("production batch step range is invalid");
        }
        long overlaps = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM orthodontic_production_batch
                        WHERE orthodontic_case_id = :caseId
                          AND batch_status <> 'CANCELLED'
                          AND step_from <= :stepTo
                          AND step_to >= :stepFrom
                        """)
                .param("caseId", caseId)
                .param("stepFrom", request.stepFrom())
                .param("stepTo", request.stepTo())
                .query(Long.class)
                .single();
        if (overlaps > 0) {
            throw conflict("production batch step range overlaps an existing batch");
        }
        int batchNo = jdbcClient.sql("""
                        SELECT COALESCE(MAX(batch_no), 0) + 1
                        FROM orthodontic_production_batch
                        WHERE orthodontic_case_id = :caseId
                        FOR UPDATE
                        """)
                .param("caseId", caseId)
                .query(Integer.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO orthodontic_production_batch
                            (orthodontic_case_id, plan_version_id, batch_no,
                             step_from, step_to, quantity, created_by_user_id)
                        VALUES
                            (:caseId, :planId, :batchNo, :stepFrom, :stepTo,
                             :quantity, :userId)
                        """)
                .param("caseId", caseId)
                .param("planId", request.planVersionId())
                .param("batchNo", batchNo)
                .param("stepFrom", request.stepFrom())
                .param("stepTo", request.stepTo())
                .param("quantity", request.stepTo() - request.stepFrom() + 1)
                .param("userId", identity.userId())
                .update();
        updateCaseStatus(caseId, "PRODUCTION");
        audit(caseId, "PRODUCTION_BATCH", null, "CREATE_BATCH", null, Map.of(
                "batch_no", batchNo,
                "step_from", request.stepFrom(),
                "step_to", request.stepTo()), identity, null);
        return caseResponse(caseId);
    }

    @Transactional
    public Map<String, Object> createChangeRequest(
            long orderId,
            CreateChangeRequest request,
            BootstrapIdentity identity) {
        OrderScope order = requireOrder(orderId);
        requireDoctorOrInternal(order, identity);
        long caseId = requireCaseId(orderId);
        PlanScope plan = requirePlan(request.sourcePlanVersionId());
        if (plan.caseId() != caseId) {
            throw badRequest("source plan does not belong to this orthodontic case");
        }
        if (request.sourceBatchId() != null) {
            long batchCount = jdbcClient.sql("""
                            SELECT COUNT(*) FROM orthodontic_production_batch
                            WHERE production_batch_id = :batchId
                              AND orthodontic_case_id = :caseId
                            """)
                    .param("batchId", request.sourceBatchId())
                    .param("caseId", caseId)
                    .query(Long.class)
                    .single();
            if (batchCount == 0) {
                throw badRequest("source batch does not belong to this orthodontic case");
            }
        }
        jdbcClient.sql("""
                        INSERT INTO orthodontic_change_request
                            (orthodontic_case_id, source_plan_version_id, source_batch_id,
                             request_type, reason, requested_by_user_id)
                        VALUES
                            (:caseId, :planId, :batchId, :requestType, :reason, :userId)
                        """)
                .param("caseId", caseId)
                .param("planId", request.sourcePlanVersionId())
                .param("batchId", request.sourceBatchId())
                .param("requestType", request.requestType())
                .param("reason", request.reason().trim())
                .param("userId", identity.userId())
                .update();
        audit(caseId, "CHANGE_REQUEST", null, "CREATE_" + request.requestType(), null, Map.of(
                "source_plan_version_id", request.sourcePlanVersionId(),
                "request_type", request.requestType()), identity, request.reason());
        Map<String, Object> response = caseResponse(caseId);
        return identity.role() == UserRole.DOCTOR ? doctorCaseResponse(response) : response;
    }

    private Map<String, Object> caseResponse(long caseId) {
        Map<String, Object> result;
        try {
            result = jdbcClient.sql("""
                            SELECT orthodontic_case_id, order_id, aligner_type_code,
                                   combined_order_id, case_status, prescription_version,
                                   prescription_json, total_steps, lock_version,
                                   created_at, updated_at
                            FROM orthodontic_case
                            WHERE orthodontic_case_id = :caseId
                            """)
                    .param("caseId", caseId)
                    .query((rs, rowNum) -> {
                        Map<String, Object> row = rowMap(rs);
                        row.put("configured", true);
                        row.put("prescription", readJson(rs.getString("prescription_json")));
                        row.remove("prescription_json");
                        return row;
                    })
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "orthodontic case not found");
        }
        result.put("plan_versions", queryRows("""
                SELECT plan_version_id, version_no, plan_status, plan_file_id,
                       plan_snapshot_json, design_note, created_by_user_id, created_at
                FROM orthodontic_plan_version
                WHERE orthodontic_case_id = :caseId
                ORDER BY version_no DESC
                """, caseId));
        result.put("reviews", queryRows("""
                SELECT review.plan_review_id, review.plan_version_id, review.review_gate,
                       review.decision, review.reason, review.reviewer_user_id, review.reviewed_at
                FROM orthodontic_plan_review review
                JOIN orthodontic_plan_version plan
                  ON plan.plan_version_id = review.plan_version_id
                WHERE plan.orthodontic_case_id = :caseId
                ORDER BY review.reviewed_at, review.plan_review_id
                """, caseId));
        result.put("production_batches", queryRows("""
                SELECT production_batch_id, plan_version_id, batch_no, step_from, step_to,
                       quantity, batch_status, lock_version, created_by_user_id,
                       created_at, updated_at
                FROM orthodontic_production_batch
                WHERE orthodontic_case_id = :caseId
                ORDER BY batch_no
                """, caseId));
        result.put("change_requests", queryRows("""
                SELECT change_request_id, source_plan_version_id, source_batch_id,
                       request_type, request_status, reason, requested_by_user_id,
                       reviewed_by_user_id, reviewed_at, created_at
                FROM orthodontic_change_request
                WHERE orthodontic_case_id = :caseId
                ORDER BY created_at DESC, change_request_id DESC
                """, caseId));
        return result;
    }

    /**
     * 医生端只返回医生完成处方和方案确认所需的业务字段。内审、生产批次和员工标识
     * 属于内部生产信息，即使验收账号在医生端拥有全功能也不得返回。
     */
    private Map<String, Object> doctorCaseResponse(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        result.remove("production_batches");
        if (Set.of("PLAN_DESIGN", "INTERNAL_REVIEW").contains(result.get("case_status"))) {
            result.put("case_status", "PLAN_PROCESSING");
        }
        result.put("plan_versions", sanitizedRows(
                source.get("plan_versions"),
                Set.of("created_by_user_id"),
                row -> !Set.of("PENDING_INTERNAL_REVIEW", "INTERNAL_REJECTED")
                        .contains(row.get("plan_status"))));
        result.put("reviews", sanitizedRows(
                source.get("reviews"),
                Set.of("reviewer_user_id"),
                row -> "DOCTOR".equals(row.get("review_gate"))));
        result.put("change_requests", sanitizedRows(
                source.get("change_requests"),
                Set.of("requested_by_user_id", "reviewed_by_user_id", "source_batch_id"),
                null));
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sanitizedRows(
            Object value,
            Set<String> removedFields,
            java.util.function.Predicate<Map<String, Object>> filter) {
        if (!(value instanceof List<?> rows)) {
            return List.of();
        }
        return rows.stream()
                .filter(Map.class::isInstance)
                .map(row -> (Map<String, Object>) new LinkedHashMap<>((Map<String, Object>) row))
                .filter(row -> filter == null || filter.test(row))
                .peek(row -> removedFields.forEach(row::remove))
                .toList();
    }

    private List<Map<String, Object>> queryRows(String sql, long caseId) {
        return jdbcClient.sql(sql)
                .param("caseId", caseId)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = rowMap(rs);
                    Object snapshot = row.get("plan_snapshot_json");
                    if (snapshot instanceof String value) {
                        row.put("plan_snapshot", readJson(value));
                        row.remove("plan_snapshot_json");
                    }
                    return row;
                })
                .list();
    }

    private Map<String, Object> planSnapshot(long planId) {
        return jdbcClient.sql("""
                        SELECT plan_version_id, orthodontic_case_id, version_no, plan_status,
                               plan_file_id, plan_snapshot_json, design_note,
                               created_by_user_id, created_at
                        FROM orthodontic_plan_version
                        WHERE plan_version_id = :planId
                        """)
                .param("planId", planId)
                .query((rs, rowNum) -> rowMap(rs))
                .single();
    }

    private void transitionPlan(long planId, String expectedStatus, String nextStatus) {
        int updated = jdbcClient.sql("""
                        UPDATE orthodontic_plan_version
                        SET plan_status = :nextStatus
                        WHERE plan_version_id = :planId
                          AND plan_status = :expectedStatus
                        """)
                .param("nextStatus", nextStatus)
                .param("planId", planId)
                .param("expectedStatus", expectedStatus)
                .update();
        if (updated == 0) {
            throw conflict("orthodontic plan changed concurrently; refresh and retry");
        }
    }

    private void recordReview(
            long planId,
            String gate,
            ReviewPlanRequest request,
            BootstrapIdentity identity) {
        jdbcClient.sql("""
                        INSERT INTO orthodontic_plan_review
                            (plan_version_id, review_gate, decision, reason, reviewer_user_id)
                        VALUES (:planId, :gate, :decision, :reason, :userId)
                        """)
                .param("planId", planId)
                .param("gate", gate)
                .param("decision", request.decision())
                .param("reason", request.reason())
                .param("userId", identity.userId())
                .update();
    }

    private void requireRejectReason(ReviewPlanRequest request) {
        if ("REJECT".equals(request.decision())
                && (request.reason() == null || request.reason().isBlank())) {
            throw badRequest("rejection reason is required");
        }
    }

    private void updateCaseStatus(long caseId, String status) {
        jdbcClient.sql("""
                        UPDATE orthodontic_case
                        SET case_status = :status,
                            lock_version = lock_version + 1
                        WHERE orthodontic_case_id = :caseId
                        """)
                .param("status", status)
                .param("caseId", caseId)
                .update();
    }

    private void validatePlanFile(Long fileId, BootstrapIdentity identity) {
        if (fileId == null) {
            return;
        }
        long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM file_resource
                        WHERE file_id = :fileId
                          AND status = 'ACTIVE'
                          AND upload_status = 'COMPLETED'
                          AND (:isAdmin = 1 OR owner_user_id = :userId)
                        """)
                .param("fileId", fileId)
                .param("isAdmin", identity.role() == UserRole.ADMIN ? 1 : 0)
                .param("userId", identity.userId())
                .query(Long.class)
                .single();
        if (count == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "plan file is not accessible");
        }
    }

    private void requireCombinedOrder(OrderScope order, Long combinedOrderId) {
        if (combinedOrderId == null) {
            return;
        }
        long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM orders
                        WHERE order_id = :combinedOrderId
                          AND group_id = :groupId
                          AND order_id <> :orderId
                        """)
                .param("combinedOrderId", combinedOrderId)
                .param("groupId", order.groupId())
                .param("orderId", order.orderId())
                .query(Long.class)
                .single();
        if (count == 0) {
            throw badRequest("combined treatment order must be another child in the same case group");
        }
    }

    private void requireConfiguredAlignerType(long orderId, String alignerTypeCode) {
        long configured = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM orders selected_order
                        JOIN catalog_product_v2 product
                          ON product.product_id = selected_order.product_id
                        JOIN catalog_config_version version
                          ON version.config_version_id = product.config_version_id
                        LEFT JOIN catalog_product_variant_v2 variant
                          ON variant.product_id = product.product_id
                         AND variant.status = 'ACTIVE'
                        LEFT JOIN catalog_rule_v2 rule
                          ON rule.config_version_id = product.config_version_id
                         AND rule.status = 'ACTIVE'
                         AND rule.rule_type IN ('FORM_SCHEMA', 'WORKFLOW')
                         AND (rule.product_id IS NULL OR rule.product_id = product.product_id)
                        WHERE selected_order.order_id = :orderId
                          AND version.publication_status = 'ACTIVE'
                          AND version.effective_at <= CURRENT_TIMESTAMP(3)
                          AND (
                              variant.variant_code = :alignerTypeCode
                              OR JSON_SEARCH(
                                  rule.rule_schema_json,
                                  'one',
                                  :alignerTypeCode,
                                  NULL,
                                  '$.aligner_types[*].code'
                              ) IS NOT NULL
                          )
                        """)
                .param("orderId", orderId)
                .param("alignerTypeCode", alignerTypeCode)
                .query(Long.class)
                .single();
        if (configured == 0) {
            throw badRequest("aligner_type_code is not enabled by the published catalog");
        }
    }

    private void requireSectionObject(JsonNode section, String name) {
        if (!section.isObject()) {
            throw badRequest(name + " must be an object");
        }
    }

    private void requireOrthodontic(OrderScope order) {
        if (!ORTHODONTIC_PRODUCT_TYPES.contains(order.productType())) {
            throw badRequest("order is not an orthodontic product");
        }
    }

    private void requireDoctorOwner(OrderScope order, BootstrapIdentity identity) {
        if (identity.role() != UserRole.DOCTOR
                || !Objects.equals(identity.userId(), order.doctorUserId())
                || !Objects.equals(identity.clinicId(), order.clinicId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "doctor cannot access this orthodontic order");
        }
    }

    private void requireRead(OrderScope order, BootstrapIdentity identity) {
        if (identity.role() == UserRole.DOCTOR) {
            requireDoctorOwner(order, identity);
            return;
        }
        requireInternalRead(order, identity);
    }

    private void requireDoctorOrInternal(OrderScope order, BootstrapIdentity identity) {
        if (identity.role() == UserRole.DOCTOR) {
            requireDoctorOwner(order, identity);
        } else {
            requireInternalRead(order, identity);
        }
    }

    private void requireInternalRead(OrderScope order, BootstrapIdentity identity) {
        if (identity.role() == UserRole.ADMIN || identity.role() == UserRole.CS) {
            return;
        }
        if (identity.role() != UserRole.WORKER || identity.userId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "internal orthodontic access is required");
        }
        long assigned = jdbcClient.sql("""
                        SELECT
                            (SELECT COUNT(*) FROM design_task
                             WHERE order_id = :orderId AND assigned_user_id = :userId)
                          + (SELECT COUNT(*)
                             FROM order_process_node node
                             JOIN order_process_instance instance
                               ON instance.instance_id = node.instance_id
                             WHERE instance.order_id = :orderId
                               AND node.assigned_user_id = :userId)
                        """)
                .param("orderId", order.orderId())
                .param("userId", identity.userId())
                .query(Long.class)
                .single();
        if (assigned == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "worker is not assigned to this orthodontic order");
        }
    }

    private void requirePortalPermission(
            BootstrapIdentity identity, String permissionCode, Set<UserRole> allowedPortals) {
        boolean portalAllowed = allowedPortals.contains(identity.role());
        boolean permissionAllowed = identity.hasPermission(permissionCode);
        if (!portalAllowed || !permissionAllowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "internal portal permission is required");
        }
    }

    private OrderScope requireOrder(long orderId) {
        try {
            return jdbcClient.sql("""
                            SELECT order_id, group_id, clinic_id, doctor_user_id,
                                   product_type, internal_status
                            FROM orders
                            WHERE order_id = :orderId
                            """)
                    .param("orderId", orderId)
                    .query((rs, rowNum) -> new OrderScope(
                            rs.getLong("order_id"),
                            rs.getObject("group_id", Long.class),
                            rs.getLong("clinic_id"),
                            rs.getObject("doctor_user_id", Long.class),
                            rs.getString("product_type"),
                            rs.getString("internal_status")))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found");
        }
    }

    private Long findCaseId(long orderId) {
        return jdbcClient.sql("""
                        SELECT orthodontic_case_id FROM orthodontic_case WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    private long requireCaseId(long orderId) {
        Long caseId = findCaseId(orderId);
        if (caseId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "orthodontic case not found");
        }
        return caseId;
    }

    private String caseStatus(long caseId) {
        return jdbcClient.sql("""
                        SELECT case_status FROM orthodontic_case WHERE orthodontic_case_id = :caseId
                        """)
                .param("caseId", caseId)
                .query(String.class)
                .single();
    }

    private PlanScope requirePlan(long planId) {
        try {
            return jdbcClient.sql("""
                            SELECT plan.plan_version_id, plan.orthodontic_case_id,
                                   plan.plan_status, orthodontic.order_id
                            FROM orthodontic_plan_version plan
                            JOIN orthodontic_case orthodontic
                              ON orthodontic.orthodontic_case_id = plan.orthodontic_case_id
                            WHERE plan.plan_version_id = :planId
                            """)
                    .param("planId", planId)
                    .query((rs, rowNum) -> new PlanScope(
                            rs.getLong("plan_version_id"),
                            rs.getLong("orthodontic_case_id"),
                            rs.getString("plan_status"),
                            rs.getLong("order_id")))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "orthodontic plan not found");
        }
    }

    private void audit(
            long caseId,
            String entityType,
            Long entityId,
            String action,
            Object before,
            Object after,
            BootstrapIdentity identity,
            String reason) {
        jdbcClient.sql("""
                        INSERT INTO orthodontic_audit
                            (orthodontic_case_id, entity_type, entity_id, action_type,
                             before_json, after_json, actor_user_id, reason)
                        VALUES
                            (:caseId, :entityType, :entityId, :action,
                             CAST(:before AS JSON), CAST(:after AS JSON), :userId, :reason)
                        """)
                .param("caseId", caseId)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("action", action)
                .param("before", before == null ? null : json(before))
                .param("after", after == null ? null : json(after))
                .param("userId", identity.userId())
                .param("reason", reason)
                .update();
    }

    private static Map<String, Object> rowMap(ResultSet rs) throws SQLException {
        ResultSetMetaData metadata = rs.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            String label = metadata.getColumnLabel(index);
            Object value = rs.getObject(index);
            if (value instanceof LocalDateTime dateTime) {
                value = dateTime.toString();
            }
            row.put(label, value);
        }
        return row;
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("invalid orthodontic JSON", ex);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot serialize orthodontic data", ex);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record OrderScope(
            long orderId,
            Long groupId,
            long clinicId,
            Long doctorUserId,
            String productType,
            String internalStatus) {
    }

    private record PlanScope(long planId, long caseId, String status, long orderId) {
    }
}
