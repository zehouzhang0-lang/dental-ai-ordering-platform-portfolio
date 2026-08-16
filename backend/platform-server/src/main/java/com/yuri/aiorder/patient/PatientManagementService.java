package com.yuri.aiorder.patient;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.auth.AccessControlService;
import com.yuri.aiorder.order.api.OrderProjectionQueryService.OrderListResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PatientManagementService {

    private final JdbcClient jdbcClient;
    private final AccessControlService accessControlService;

    public PatientManagementService(JdbcClient jdbcClient, AccessControlService accessControlService) {
        this.jdbcClient = jdbcClient;
        this.accessControlService = accessControlService;
    }

    @Transactional
    public PatientRecordResponse createPatient(CreatePatientRequest request, BootstrapIdentity identity) {
        accessControlService.requireDoctorPortalAction(
                identity, "patient:manage-doctor", "only doctors can manage patient records");
        accessControlService.requireScopedIdentity(identity, "CLINIC");
        String patientName = normalizeRequired(request.patientName(), "patient_name is required");
        String treatmentStatus = normalizeTreatmentStatus(request.treatmentStatus());

        jdbcClient.sql("""
                        INSERT INTO patient_record
                            (clinic_id, doctor_user_id, patient_name, patient_age, patient_gender,
                             date_of_birth, phone, email, medical_notes, patient_tags,
                             treatment_status, treatment_started_at, treatment_ended_at, oral_description)
                        VALUES
                            (:clinicId, :doctorUserId, :patientName, :patientAge, :patientGender,
                             :dateOfBirth, :phone, :email, :medicalNotes, :patientTags,
                             :treatmentStatus, COALESCE(:treatmentStartedAt, CURRENT_DATE), :treatmentEndedAt, :oralDescription)
                        """)
                .param("clinicId", identity.clinicId())
                .param("doctorUserId", identity.userId())
                .param("patientName", patientName)
                .param("patientAge", request.patientAge())
                .param("patientGender", normalizeNullable(request.patientGender()))
                .param("dateOfBirth", request.dateOfBirth())
                .param("phone", normalizeNullable(request.phone()))
                .param("email", normalizeNullable(request.email()))
                .param("medicalNotes", normalizeNullable(request.medicalNotes()))
                .param("patientTags", normalizeNullable(request.tags()))
                .param("treatmentStatus", treatmentStatus)
                .param("treatmentStartedAt", request.treatmentStartedAt())
                .param("treatmentEndedAt", normalizeTreatmentEndDate(treatmentStatus, request.treatmentEndedAt()))
                .param("oralDescription", normalizeNullable(request.oralDescription()))
                .update();

        long patientId = jdbcClient.sql("SELECT LAST_INSERT_ID()")
                .query(Long.class)
                .single();
        return loadOwnedPatient(patientId, identity);
    }

    public PatientRecordResponse getPatient(long patientId, BootstrapIdentity identity) {
        return loadOwnedPatient(patientId, identity);
    }

    @Transactional
    public PatientRecordResponse updatePatient(long patientId, UpdatePatientRequest request, BootstrapIdentity identity) {
        loadOwnedPatient(patientId, identity);
        String patientName = normalizeRequired(request.patientName(), "patient_name is required");
        String treatmentStatus = normalizeTreatmentStatus(request.treatmentStatus());
        jdbcClient.sql("""
                        UPDATE patient_record
                        SET patient_name = :patientName,
                            patient_age = :patientAge,
                            patient_gender = :patientGender,
                            date_of_birth = :dateOfBirth,
                            phone = :phone,
                            email = :email,
                            medical_notes = :medicalNotes,
                            patient_tags = :patientTags,
                            treatment_status = :treatmentStatus,
                            treatment_started_at = :treatmentStartedAt,
                            treatment_ended_at = :treatmentEndedAt,
                            oral_description = :oralDescription
                        WHERE patient_id = :patientId
                          AND clinic_id = :clinicId
                          AND doctor_user_id = :doctorUserId
                        """)
                .param("patientName", patientName)
                .param("patientAge", request.patientAge())
                .param("patientGender", normalizeNullable(request.patientGender()))
                .param("dateOfBirth", request.dateOfBirth())
                .param("phone", normalizeNullable(request.phone()))
                .param("email", normalizeNullable(request.email()))
                .param("medicalNotes", normalizeNullable(request.medicalNotes()))
                .param("patientTags", normalizeNullable(request.tags()))
                .param("treatmentStatus", treatmentStatus)
                .param("treatmentStartedAt", request.treatmentStartedAt())
                .param("treatmentEndedAt", normalizeTreatmentEndDate(treatmentStatus, request.treatmentEndedAt()))
                .param("oralDescription", normalizeNullable(request.oralDescription()))
                .param("patientId", patientId)
                .param("clinicId", identity.clinicId())
                .param("doctorUserId", identity.userId())
                .update();
        return loadOwnedPatient(patientId, identity);
    }

    public OrderListResponse<PatientRecordResponse> listPatients(
            BootstrapIdentity identity, String keyword, int page, int size) {
        accessControlService.requireDoctorPortalAction(
                identity, "patient:manage-doctor", "only doctors can manage patient records");
        accessControlService.requireScopedIdentity(identity, "CLINIC");
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(size, 100));
        int offset = (safePage - 1) * safeSize;

        List<String> filters = new ArrayList<>();
        filters.add("p.clinic_id = :clinicId");
        filters.add("p.doctor_user_id = :doctorUserId");
        filters.add("p.status = 'ACTIVE'");
        if (keyword != null && !keyword.isBlank()) {
            filters.add("(p.patient_name LIKE :keyword OR p.patient_code LIKE :keyword OR CONCAT('P-', p.patient_id) LIKE :keyword OR p.phone LIKE :keyword OR p.email LIKE :keyword OR p.patient_tags LIKE :keyword)");
        }
        String whereClause = "WHERE " + String.join(" AND ", filters);

        List<PatientRecordResponse> rows = bindListParams(jdbcClient.sql("""
                        %s
                        %s
                        ORDER BY p.updated_at DESC, p.patient_id DESC
                        LIMIT :limit OFFSET :offset
                        """.formatted(basePatientSelect(), whereClause)), identity, keyword)
                .param("limit", safeSize)
                .param("offset", offset)
                .query(this::mapPatient)
                .list();
        long total = bindListParams(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM patient_record p
                        %s
                        """.formatted(whereClause)), identity, keyword)
                .query(Long.class)
                .single();
        return new OrderListResponse<>(rows, total, safePage, safeSize);
    }

    public OrderListResponse<PatientOrderResponse> listPatientOrders(
            long patientId, BootstrapIdentity identity, int page, int size) {
        PatientRecordResponse patient = loadOwnedPatient(patientId, identity);
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(size, 100));
        int offset = (safePage - 1) * safeSize;

        List<PatientOrderResponse> rows = jdbcClient.sql("""
                        SELECT order_id, order_no, product_type, external_status, created_at
                        FROM orders
                        WHERE patient_id = :patientId
                          AND clinic_id = :clinicId
                          AND doctor_user_id = :doctorUserId
                        ORDER BY created_at DESC, order_id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("patientId", patient.patientId())
                .param("clinicId", identity.clinicId())
                .param("doctorUserId", identity.userId())
                .param("limit", safeSize)
                .param("offset", offset)
                .query((rs, rowNum) -> new PatientOrderResponse(
                        rs.getLong("order_id"),
                        rs.getString("order_no"),
                        rs.getString("product_type"),
                        rs.getString("external_status"),
                        rs.getObject("created_at", LocalDateTime.class)))
                .list();
        long total = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM orders
                        WHERE patient_id = :patientId
                          AND clinic_id = :clinicId
                          AND doctor_user_id = :doctorUserId
                        """)
                .param("patientId", patient.patientId())
                .param("clinicId", identity.clinicId())
                .param("doctorUserId", identity.userId())
                .query(Long.class)
                .single();
        return new OrderListResponse<>(rows, total, safePage, safeSize);
    }

    private PatientRecordResponse loadOwnedPatient(long patientId, BootstrapIdentity identity) {
        accessControlService.requireDoctorPortalAction(
                identity, "patient:manage-doctor", "only doctors can manage patient records");
        accessControlService.requireScopedIdentity(identity, "CLINIC");
        try {
            return jdbcClient.sql("""
                            %s
                            WHERE p.patient_id = :patientId
                              AND p.clinic_id = :clinicId
                              AND p.doctor_user_id = :doctorUserId
                              AND p.status = 'ACTIVE'
                            """.formatted(basePatientSelect()))
                    .param("patientId", patientId)
                    .param("clinicId", identity.clinicId())
                    .param("doctorUserId", identity.userId())
                    .query(this::mapPatient)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            if (patientExists(patientId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "doctor cannot access this patient", ex);
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "patient not found", ex);
        }
    }

    private boolean patientExists(long patientId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM patient_record WHERE patient_id = :patientId")
                .param("patientId", patientId)
                .query(Long.class)
                .single() > 0;
    }

    private String basePatientSelect() {
        return """
                SELECT
                    p.patient_id,
                    p.clinic_id,
                    p.doctor_user_id,
                    p.patient_code,
                    p.patient_name,
                    p.patient_age,
                    p.patient_gender,
                    p.date_of_birth,
                    p.phone,
                    p.email,
                    p.medical_notes,
                    p.patient_tags,
                    p.treatment_status,
                    p.treatment_started_at,
                    p.treatment_ended_at,
                    p.oral_description,
                    (
                        SELECT COUNT(*)
                        FROM orders o
                        WHERE o.patient_id = p.patient_id
                    ) AS order_count,
                    (
                        SELECT o.order_no
                        FROM orders o
                        WHERE o.patient_id = p.patient_id
                        ORDER BY o.created_at DESC, o.order_id DESC
                        LIMIT 1
                    ) AS latest_order_no,
                    (
                        SELECT o.product_type
                        FROM orders o
                        WHERE o.patient_id = p.patient_id
                        ORDER BY o.created_at DESC, o.order_id DESC
                        LIMIT 1
                    ) AS latest_product_type,
                    (
                        SELECT MAX(o.created_at)
                        FROM orders o
                        WHERE o.patient_id = p.patient_id
                    ) AS latest_order_at,
                    p.created_at,
                    p.updated_at
                FROM patient_record p
                """;
    }

    private JdbcClient.StatementSpec bindListParams(
            JdbcClient.StatementSpec spec, BootstrapIdentity identity, String keyword) {
        spec = spec.param("clinicId", identity.clinicId())
                .param("doctorUserId", identity.userId());
        if (keyword != null && !keyword.isBlank()) {
            spec = spec.param("keyword", "%" + keyword.trim() + "%");
        }
        return spec;
    }

    private PatientRecordResponse mapPatient(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new PatientRecordResponse(
                rs.getLong("patient_id"),
                rs.getLong("clinic_id"),
                rs.getLong("doctor_user_id"),
                rs.getString("patient_code"),
                rs.getString("patient_name"),
                rs.getObject("patient_age", Integer.class),
                rs.getString("patient_gender"),
                rs.getObject("date_of_birth", java.time.LocalDate.class),
                rs.getString("phone"),
                rs.getString("email"),
                rs.getString("medical_notes"),
                rs.getString("patient_tags"),
                rs.getString("treatment_status"),
                rs.getObject("treatment_started_at", java.time.LocalDate.class),
                rs.getObject("treatment_ended_at", java.time.LocalDate.class),
                rs.getString("oral_description"),
                rs.getLong("order_count"),
                rs.getString("latest_order_no"),
                rs.getString("latest_product_type"),
                rs.getObject("latest_order_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeTreatmentStatus(String value) {
        String normalized = value == null || value.isBlank() ? "IN_TREATMENT" : value.trim().toUpperCase();
        return switch (normalized) {
            case "IN_TREATMENT", "FOLLOW_UP", "TREATMENT_ENDED", "ARCHIVED" -> normalized;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported treatment_status");
        };
    }

    private java.time.LocalDate normalizeTreatmentEndDate(String treatmentStatus, java.time.LocalDate value) {
        if (!"TREATMENT_ENDED".equals(treatmentStatus) && !"ARCHIVED".equals(treatmentStatus)) {
            return null;
        }
        return value == null ? java.time.LocalDate.now() : value;
    }
}
