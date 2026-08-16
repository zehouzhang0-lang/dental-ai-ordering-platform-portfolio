package com.yuri.aiorder.order.form;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FormConfigService {

    private static final Set<String> ALLOWED_FIELD_TYPES = Set.of(
            "text", "textarea", "select", "multi-select", "number", "date", "file");
    private static final Set<String> ALLOWED_STATUS = Set.of("ACTIVE", "INACTIVE");

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public FormConfigService(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public List<FormFieldConfigResponse> listActiveFields(String productType) {
        String productFilter = productType == null || productType.isBlank()
                ? null
                : productType.trim().toUpperCase(java.util.Locale.ROOT);
        JdbcClient.StatementSpec spec = jdbcClient.sql("""
                        SELECT field_id, product_type, field_key, field_label, field_type,
                               options_json, required_flag, sort_order, status
                        FROM form_field_config
                        WHERE status = 'ACTIVE'
                          AND (:productType IS NULL OR product_type = :productType)
                        ORDER BY product_type, sort_order, field_id
                        """)
                .param("productType", productFilter);
        return spec.query((rs, rowNum) -> new FormFieldConfigResponse(
                        rs.getLong("field_id"),
                        rs.getString("product_type"),
                        rs.getString("field_key"),
                        rs.getString("field_label"),
                        rs.getString("field_type"),
                        rs.getInt("required_flag") == 1,
                        readOptions(rs.getString("options_json")),
                        rs.getInt("sort_order"),
                        rs.getString("status")))
                .list();
    }

    @Transactional
    public FormFieldConfigResponse createField(CreateFormFieldRequest request) {
        String productType = normalizeRequired(request.productType(), "product_type").toUpperCase(Locale.ROOT);
        String fieldKey = normalizeRequired(request.fieldKey(), "field_key");
        String fieldLabel = normalizeRequired(request.fieldLabel(), "field_label");
        String fieldType = normalizeFieldType(request.fieldType());
        String optionsJson = writeOptions(request.options());
        boolean required = Boolean.TRUE.equals(request.required());
        int sortOrder = request.sortOrder() == null ? 0 : request.sortOrder();
        try {
            jdbcClient.sql("""
                            INSERT INTO form_field_config
                                (product_type, field_key, field_label, field_type,
                                 options_json, required_flag, sort_order, status)
                            VALUES
                                (:productType, :fieldKey, :fieldLabel, :fieldType,
                                 :optionsJson, :requiredFlag, :sortOrder, 'ACTIVE')
                            """)
                    .param("productType", productType)
                    .param("fieldKey", fieldKey)
                    .param("fieldLabel", fieldLabel)
                    .param("fieldType", fieldType)
                    .param("optionsJson", optionsJson)
                    .param("requiredFlag", required ? 1 : 0)
                    .param("sortOrder", sortOrder)
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "form field already exists", ex);
        }
        long fieldId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return requireField(fieldId);
    }

    @Transactional
    public FormFieldConfigResponse updateField(long fieldId, UpdateFormFieldRequest request) {
        requireField(fieldId);
        String fieldLabel = normalizeOptional(request.fieldLabel(), "field_label");
        String optionsJson = request.options() == null ? null : writeOptions(request.options());
        String status = request.status() == null ? null : normalizeStatus(request.status());
        jdbcClient.sql("""
                        UPDATE form_field_config
                        SET field_label = COALESCE(:fieldLabel, field_label),
                            options_json = COALESCE(:optionsJson, options_json),
                            required_flag = COALESCE(:requiredFlag, required_flag),
                            sort_order = COALESCE(:sortOrder, sort_order),
                            status = COALESCE(:status, status)
                        WHERE field_id = :fieldId
                        """)
                .param("fieldLabel", fieldLabel)
                .param("optionsJson", optionsJson)
                .param("requiredFlag", request.required() == null ? null : (request.required() ? 1 : 0))
                .param("sortOrder", request.sortOrder())
                .param("status", status)
                .param("fieldId", fieldId)
                .update();
        return requireField(fieldId);
    }

    private FormFieldConfigResponse requireField(long fieldId) {
        return jdbcClient.sql("""
                        SELECT field_id, product_type, field_key, field_label, field_type,
                               options_json, required_flag, sort_order, status
                        FROM form_field_config
                        WHERE field_id = :fieldId
                        """)
                .param("fieldId", fieldId)
                .query((rs, rowNum) -> new FormFieldConfigResponse(
                        rs.getLong("field_id"),
                        rs.getString("product_type"),
                        rs.getString("field_key"),
                        rs.getString("field_label"),
                        rs.getString("field_type"),
                        rs.getInt("required_flag") == 1,
                        readOptions(rs.getString("options_json")),
                        rs.getInt("sort_order"),
                        rs.getString("status")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "form field not found"));
    }

    private List<String> readOptions(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(optionsJson, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "invalid form options json", ex);
        }
    }

    private String writeOptions(List<String> options) {
        List<String> normalized = options == null
                ? List.of()
                : new LinkedHashSet<>(options.stream()
                        .map(option -> option == null ? "" : option.trim())
                        .filter(option -> !option.isBlank())
                        .toList()).stream().toList();
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid form options", ex);
        }
    }

    private String normalizeRequired(String value, String fieldName) {
        String normalized = normalizeOptional(value, fieldName);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
        return normalized;
    }

    private String normalizeOptional(String value, String fieldName) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " cannot be blank");
        }
        return normalized;
    }

    private String normalizeFieldType(String fieldType) {
        String normalized = normalizeRequired(fieldType, "field_type").toLowerCase(Locale.ROOT);
        if (!ALLOWED_FIELD_TYPES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported field_type");
        }
        return normalized;
    }

    private String normalizeStatus(String status) {
        String normalized = normalizeRequired(status, "status").toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUS.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported status");
        }
        return normalized;
    }
}
