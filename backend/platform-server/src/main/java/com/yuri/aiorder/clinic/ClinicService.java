package com.yuri.aiorder.clinic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.AccessControlService;
import com.yuri.aiorder.order.api.OrderProjectionQueryService.OrderListResponse;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClinicService {

    private static final Set<String> ALLOWED_PREFERENCE_KEYS = Set.of(
            "color", "contact", "occlusion", "margin", "shape", "material", "note");

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;

    public ClinicService(JdbcClient jdbcClient, ObjectMapper objectMapper, AccessControlService accessControlService) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
    }

    public OrderListResponse<ClinicResponse> listClinics(BootstrapIdentity identity, String keyword, int page, int size) {
        requireInternalCustomerAccess(identity);
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(size, 100));
        int offset = (safePage - 1) * safeSize;
        String whereClause = (keyword == null || keyword.isBlank())
                ? "WHERE c.status <> 'DELETED'"
                : "WHERE c.status <> 'DELETED' AND (c.clinic_code LIKE :keyword OR c.clinic_name LIKE :keyword OR c.contact_name LIKE :keyword OR c.contact_phone LIKE :keyword)";

        JdbcClient.StatementSpec listSpec = bindKeyword(jdbcClient.sql("""
                        %s
                        %s
                        ORDER BY updated_at DESC, c.clinic_id DESC
                        LIMIT :limit OFFSET :offset
                        """.formatted(baseClinicSelect(), whereClause)), keyword)
                .param("limit", safeSize)
                .param("offset", offset);
        List<ClinicResponse> rows = listSpec.query(this::mapClinic).list();
        long total = bindKeyword(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM clinic c
                        %s
                        """.formatted(whereClause)), keyword)
                .query(Long.class)
                .single();
        return new OrderListResponse<>(rows, total, safePage, safeSize);
    }

    @Transactional
    public ClinicResponse createClinic(CreateClinicRequest request, BootstrapIdentity identity) {
        accessControlService.requirePermission(identity, "clinic:create", "clinic creation requires clinic:create");
        String clinicName = normalizeRequired(request.clinicName(), "clinic_name is required");
        String requestedCode = normalizeClinicCode(request.clinicCode());
        String temporaryCode = requestedCode == null
                ? "TMP" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT)
                : requestedCode;
        try {
            jdbcClient.sql("""
                            INSERT INTO clinic (
                                clinic_code, clinic_name, contact_name, contact_phone, contact_email,
                                business_region, salesperson, customer_type, settlement_type,
                                organization_nature, business_level, default_shipping_method, status)
                            VALUES (
                                :clinicCode, :clinicName, :contactName, :contactPhone, :contactEmail,
                                :businessRegion, :salesperson, :customerType, :settlementType,
                                :organizationNature, :businessLevel, :defaultShippingMethod, 'ACTIVE')
                            """)
                    .param("clinicCode", temporaryCode)
                    .param("clinicName", clinicName)
                    .param("contactName", normalizeNullable(request.contactName()))
                    .param("contactPhone", normalizeNullable(request.contactPhone()))
                    .param("contactEmail", normalizeNullable(request.contactEmail()))
                    .param("businessRegion", normalizeNullable(request.businessRegion()))
                    .param("salesperson", normalizeNullable(request.salesperson()))
                    .param("customerType", normalizeNullable(request.customerType()))
                    .param("settlementType", normalizeNullable(request.settlementType()))
                    .param("organizationNature", normalizeNullable(request.organizationNature()))
                    .param("businessLevel", normalizeNullable(request.businessLevel()))
                    .param("defaultShippingMethod", normalizeNullable(request.defaultShippingMethod()))
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "clinic name already exists", ex);
        }
        long clinicId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        if (requestedCode == null) {
            jdbcClient.sql("UPDATE clinic SET clinic_code = :clinicCode WHERE clinic_id = :clinicId")
                    .param("clinicCode", "KH" + String.format("%06d", clinicId))
                    .param("clinicId", clinicId)
                    .update();
        }
        return getClinic(clinicId, identity);
    }

    public ClinicResponse getClinic(long clinicId, BootstrapIdentity identity) {
        requireClinicAccess(identity, clinicId);
        return loadClinic(clinicId);
    }

    public ClinicPreferenceResponse getPreference(long clinicId, BootstrapIdentity identity) {
        requireClinicAccess(identity, clinicId);
        ClinicResponse clinic = loadClinic(clinicId);
        return loadPreference(loadClinic(clinicId));
    }

    @Transactional
    public ClinicPreferenceResponse updatePreference(
            long clinicId, Map<String, Object> preferences, BootstrapIdentity identity) {
        requireInternalCustomerAccess(identity);
        ClinicResponse clinic = loadClinic(clinicId);
        Map<String, Object> normalized = normalizePreferences(preferences);
        for (String key : ALLOWED_PREFERENCE_KEYS) {
            Object value = normalized.get(key);
            if (value == null) {
                jdbcClient.sql("""
                                DELETE FROM customer_preference
                                WHERE clinic_id = :clinicId AND preference_key = :preferenceKey
                                """)
                        .param("clinicId", clinicId)
                        .param("preferenceKey", key)
                        .update();
                continue;
            }
            jdbcClient.sql("""
                            INSERT INTO customer_preference (clinic_id, preference_key, preference_value)
                            VALUES (:clinicId, :preferenceKey, CAST(:preferenceValue AS JSON))
                            ON DUPLICATE KEY UPDATE
                                preference_value = VALUES(preference_value),
                                updated_at = CURRENT_TIMESTAMP(3)
                            """)
                    .param("clinicId", clinicId)
                    .param("preferenceKey", key)
                    .param("preferenceValue", writeJson(value))
                    .update();
        }
        return loadPreference(clinic);
    }

    private void requireInternalCustomerAccess(BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, "clinic:manage", "clinic management requires clinic:manage");
    }

    private void requireClinicAccess(BootstrapIdentity identity, long clinicId) {
        if (identity.role() == UserRole.DOCTOR) {
            accessControlService.requireScopedIdentity(identity, "CLINIC");
            if (!Long.valueOf(clinicId).equals(identity.clinicId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "doctor cannot access this clinic");
            }
            return;
        }
        requireInternalCustomerAccess(identity);
    }

    private ClinicResponse loadClinic(long clinicId) {
        try {
            return jdbcClient.sql("""
                            %s
                            WHERE c.clinic_id = :clinicId
                              AND c.status <> 'DELETED'
                            """.formatted(baseClinicSelect()))
                    .param("clinicId", clinicId)
                    .query(this::mapClinic)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "clinic not found", ex);
        }
    }

    private ClinicPreferenceResponse loadPreference(ClinicResponse clinic) {
        Map<String, Object> preferences = new LinkedHashMap<>();
        for (String key : ALLOWED_PREFERENCE_KEYS) {
            preferences.put(key, null);
        }
        jdbcClient.sql("""
                        SELECT preference_key, CAST(preference_value AS CHAR) AS preference_value, updated_at
                        FROM customer_preference
                        WHERE clinic_id = :clinicId
                        ORDER BY preference_key
                        """)
                .param("clinicId", clinic.clinicId())
                .query((rs, rowNum) -> {
                    preferences.put(rs.getString("preference_key"), readJson(rs.getString("preference_value")));
                    return rs.getObject("updated_at", LocalDateTime.class);
                })
                .list();
        return new ClinicPreferenceResponse(clinic.clinicId(), clinic.clinicName(), preferences, clinic.updatedAt());
    }

    private String baseClinicSelect() {
        return """
                SELECT
                    c.clinic_id,
                    c.clinic_code,
                    c.clinic_name,
                    c.contact_name,
                    c.contact_phone,
                    c.contact_email,
                    c.business_region,
                    c.salesperson,
                    c.customer_type,
                    c.settlement_type,
                    c.organization_nature,
                    c.business_level,
                    c.default_shipping_method,
                    c.status,
                    EXISTS (
                        SELECT 1
                        FROM clinic_blacklist_record cbr
                        WHERE cbr.clinic_id = c.clinic_id
                          AND cbr.blacklist_status = 'ACTIVE'
                    ) AS blacklisted,
                    (
                        SELECT COUNT(*)
                        FROM customer_preference cp
                        WHERE cp.clinic_id = c.clinic_id
                    ) AS preference_count,
                    c.created_at,
                    GREATEST(
                        c.updated_at,
                        COALESCE((
                            SELECT MAX(cp.updated_at)
                            FROM customer_preference cp
                            WHERE cp.clinic_id = c.clinic_id
                        ), c.updated_at)
                    ) AS updated_at
                FROM clinic c
                """;
    }

    private ClinicResponse mapClinic(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ClinicResponse(
                rs.getLong("clinic_id"),
                rs.getString("clinic_code"),
                rs.getString("clinic_name"),
                rs.getString("contact_name"),
                rs.getString("contact_phone"),
                rs.getString("contact_email"),
                rs.getString("business_region"),
                rs.getString("salesperson"),
                rs.getString("customer_type"),
                rs.getString("settlement_type"),
                rs.getString("organization_nature"),
                rs.getString("business_level"),
                rs.getString("default_shipping_method"),
                rs.getString("status"),
                rs.getBoolean("blacklisted"),
                rs.getLong("preference_count"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private JdbcClient.StatementSpec bindKeyword(JdbcClient.StatementSpec spec, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return spec;
        }
        return spec.param("keyword", "%" + keyword.trim() + "%");
    }

    private Map<String, Object> normalizePreferences(Map<String, Object> preferences) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (preferences == null) {
            return normalized;
        }
        for (Map.Entry<String, Object> entry : preferences.entrySet()) {
            String key = entry.getKey();
            if (!ALLOWED_PREFERENCE_KEYS.contains(key)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported preference key: " + key);
            }
            Object value = entry.getValue();
            if (value instanceof String stringValue && stringValue.isBlank()) {
                normalized.put(key, null);
            } else {
                normalized.put(key, value);
            }
        }
        return normalized;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid preference value", ex);
        }
    }

    private Object readJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "invalid stored preference value", ex);
        }
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

    private String normalizeClinicCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_-]{2,32}")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "clinic_code must use A-Z, 0-9, underscore or dash");
        }
        return normalized;
    }
}
