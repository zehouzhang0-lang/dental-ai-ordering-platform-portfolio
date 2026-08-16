package com.yuri.aiorder.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CatalogVersionResponse;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CreateAccessoryBindingRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CreateAccessoryRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CreateAliasRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CreateCatalogRuleRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CreateCatalogVersionRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CreateMaterialColorRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CreateVariantRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.UpdateCatalogRuleRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.UpdateAccessoryBindingRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.UpdateAliasRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.UpdateMaterialBindingRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.UpdateNamedCatalogEntityRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.UpdateProductRequest;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CatalogExtendedManagementService {

    private static final Pattern IMPORT_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,95}");
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final CatalogRuleSchemaValidator ruleSchemaValidator;

    public CatalogExtendedManagementService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            CatalogRuleSchemaValidator ruleSchemaValidator) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.ruleSchemaValidator = ruleSchemaValidator;
    }

    public Map<String, Object> importTemplate(BootstrapIdentity identity) {
        requireManage(identity);
        return Map.of(
                "template_version", "CATALOG_V2_1",
                "encoding", "UTF-8",
                "format", "CSV_OR_JSON",
                "required_columns", List.of("entity_type", "code", "display_name"),
                "optional_columns", List.of(
                        "parent_code", "brand_name", "specification", "semantic_type",
                        "selection_group_code", "selection_mode", "min_quantity",
                        "max_quantity", "price_increment_cents", "sort_order", "status"),
                "supported_entity_types", List.of(
                        "CATEGORY", "PRODUCT", "VARIANT", "MATERIAL", "MATERIAL_COLOR",
                        "ACCESSORY", "MATERIAL_BINDING", "ACCESSORY_BINDING", "ALIAS", "RULE"),
                "writes_data", false);
    }

    public Map<String, Object> validateImport(JsonNode request, BootstrapIdentity identity) {
        requireManage(identity);
        if (request == null || !request.isObject()) {
            throw badRequest("import validation body must be an object");
        }
        if (!"CATALOG_V2_1".equals(request.path("template_version").asText())) {
            throw badRequest("unsupported catalog import template_version");
        }
        JsonNode rows = request.path("rows");
        if (!rows.isArray()) {
            throw badRequest("rows must be an array");
        }
        if (rows.size() > 5000) {
            throw badRequest("import validation accepts at most 5000 rows");
        }
        Set<String> supported = Set.of(
                "CATEGORY", "PRODUCT", "VARIANT", "MATERIAL", "MATERIAL_COLOR",
                "ACCESSORY", "MATERIAL_BINDING", "ACCESSORY_BINDING", "ALIAS", "RULE");
        Set<String> identities = new HashSet<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        int rowNumber = 0;
        for (JsonNode row : rows) {
            rowNumber++;
            if (!row.isObject()) {
                errors.add(importError(rowNumber, "INVALID_ROW", "row must be an object"));
                continue;
            }
            String type = row.path("entity_type").asText("").trim().toUpperCase(Locale.ROOT);
            String code = row.path("code").asText("").trim().toUpperCase(Locale.ROOT);
            String name = row.path("display_name").asText("").trim();
            if (!supported.contains(type)) {
                errors.add(importError(rowNumber, "UNSUPPORTED_ENTITY_TYPE", "unsupported entity_type"));
            }
            if (!IMPORT_CODE.matcher(code).matches()) {
                errors.add(importError(rowNumber, "INVALID_CODE", "code must be a stable uppercase code"));
            }
            if (name.isEmpty() || name.length() > 128) {
                errors.add(importError(rowNumber, "INVALID_DISPLAY_NAME", "display_name is required and at most 128 characters"));
            }
            if (!type.isEmpty() && !code.isEmpty() && !identities.add(type + ":" + code)) {
                errors.add(importError(rowNumber, "DUPLICATE_CODE", "entity_type and code are duplicated in this file"));
            }
        }
        return Map.of(
                "template_version", "CATALOG_V2_1",
                "row_count", rows.size(),
                "valid", errors.isEmpty(),
                "error_count", errors.size(),
                "errors", errors,
                "writes_data", false);
    }

    private static Map<String, Object> importError(int row, String code, String message) {
        return Map.of("row", row, "code", code, "message", message);
    }

    @Transactional
    public CatalogVersionResponse createVersion(
            CreateCatalogVersionRequest request, BootstrapIdentity identity) {
        requireManage(identity);
        Long basedOn = request.basedOnVersionId();
        if (basedOn != null) {
            requireVersionExists(basedOn);
        }
        int versionNo = jdbcClient.sql("""
                        SELECT COALESCE(MAX(version_no), 0) + 1
                        FROM catalog_config_version
                        FOR UPDATE
                        """)
                .query(Integer.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO catalog_config_version
                            (version_no, version_name, publication_status,
                             based_on_version_id, created_by_user_id)
                        VALUES
                            (:versionNo, :versionName, 'DRAFT', :basedOn, :userId)
                        """)
                .param("versionNo", versionNo)
                .param("versionName", request.versionName().trim())
                .param("basedOn", basedOn)
                .param("userId", identity.userId())
                .update();
        long versionId = jdbcClient.sql("""
                        SELECT config_version_id
                        FROM catalog_config_version
                        WHERE version_no = :versionNo
                        """)
                .param("versionNo", versionNo)
                .query(Long.class)
                .single();
        if (basedOn != null) {
            copyVersion(basedOn, versionId);
        }
        CatalogVersionResponse result = loadVersion(versionId);
        audit(versionId, "CONFIG_VERSION", versionId, "CREATE_DRAFT", null, result, identity.userId(), null);
        return result;
    }

    @Transactional
    public Map<String, Object> createVariant(
            long versionId, CreateVariantRequest request, BootstrapIdentity identity) {
        requireManage(identity);
        requireDraft(versionId);
        requireProduct(request.productId(), versionId);
        try {
            jdbcClient.sql("""
                            INSERT INTO catalog_product_variant_v2
                                (config_version_id, product_id, variant_code,
                                 display_name, attributes_json, sort_order)
                            VALUES
                                (:versionId, :productId, :code,
                                 :name, CAST(:attributes AS JSON), :sortOrder)
                            """)
                    .param("versionId", versionId)
                    .param("productId", request.productId())
                    .param("code", normalizeCode(request.variantCode()))
                    .param("name", request.displayName().trim())
                    .param("attributes", jsonOrNull(request.attributes()))
                    .param("sortOrder", Objects.requireNonNullElse(request.sortOrder(), 0))
                    .update();
        } catch (DuplicateKeyException ex) {
            throw conflict("variant code already exists in this draft", ex);
        }
        long id = latestId("catalog_product_variant_v2", "variant_id", versionId);
        Map<String, Object> after = entitySnapshot("VARIANT", id);
        audit(versionId, "PRODUCT_VARIANT", id, "CREATE", null, after, identity.userId(), null);
        return after;
    }

    @Transactional
    public Map<String, Object> createAccessory(
            long versionId, CreateAccessoryRequest request, BootstrapIdentity identity) {
        requireManage(identity);
        requireDraft(versionId);
        try {
            jdbcClient.sql("""
                            INSERT INTO catalog_accessory_v2
                                (config_version_id, accessory_code, display_name,
                                 quantity_supported, sort_order)
                            VALUES
                                (:versionId, :code, :name, :quantitySupported, :sortOrder)
                            """)
                    .param("versionId", versionId)
                    .param("code", normalizeCode(request.accessoryCode()))
                    .param("name", request.displayName().trim())
                    .param("quantitySupported", !Boolean.FALSE.equals(request.quantitySupported()))
                    .param("sortOrder", Objects.requireNonNullElse(request.sortOrder(), 0))
                    .update();
        } catch (DuplicateKeyException ex) {
            throw conflict("accessory code already exists in this draft", ex);
        }
        long id = latestId("catalog_accessory_v2", "accessory_id", versionId);
        Map<String, Object> after = entitySnapshot("ACCESSORY", id);
        audit(versionId, "ACCESSORY", id, "CREATE", null, after, identity.userId(), null);
        return after;
    }

    @Transactional
    public Map<String, Object> createMaterialColor(
            long versionId, CreateMaterialColorRequest request, BootstrapIdentity identity) {
        requireManage(identity);
        requireDraft(versionId);
        requireMaterial(request.materialId(), versionId);
        try {
            jdbcClient.sql("""
                            INSERT INTO catalog_material_color_v2
                                (material_id, semantic_type, color_code, display_name, sort_order)
                            VALUES
                                (:materialId, :semanticType, :colorCode, :name, :sortOrder)
                            """)
                    .param("materialId", request.materialId())
                    .param("semanticType", request.semanticType())
                    .param("colorCode", request.colorCode().trim())
                    .param("name", request.displayName().trim())
                    .param("sortOrder", Objects.requireNonNullElse(request.sortOrder(), 0))
                    .update();
        } catch (DuplicateKeyException ex) {
            throw conflict("material color already exists", ex);
        }
        long id = jdbcClient.sql("""
                        SELECT material_color_id
                        FROM catalog_material_color_v2
                        WHERE material_id = :materialId
                        ORDER BY material_color_id DESC
                        LIMIT 1
                        """)
                .param("materialId", request.materialId())
                .query(Long.class)
                .single();
        Map<String, Object> after = entitySnapshot("MATERIAL_COLOR", id);
        audit(versionId, "MATERIAL_COLOR", id, "CREATE", null, after, identity.userId(), null);
        return after;
    }

    @Transactional
    public Map<String, Object> bindAccessory(
            long versionId, CreateAccessoryBindingRequest request, BootstrapIdentity identity) {
        requireManage(identity);
        requireDraft(versionId);
        requireProduct(request.productId(), versionId);
        requireAccessory(request.accessoryId(), versionId);
        requireVariant(request.variantId(), request.productId(), versionId);
        validateQuantityRange(request.minQuantity(), request.maxQuantity());
        try {
            jdbcClient.sql("""
                            INSERT INTO catalog_product_accessory_binding_v2
                                (config_version_id, product_id, variant_id, accessory_id,
                                 selection_group_code, required_flag, default_flag,
                                 min_quantity, max_quantity, price_increment_cents, sort_order)
                            VALUES
                                (:versionId, :productId, :variantId, :accessoryId,
                                 :selectionGroup, :required, :defaultValue,
                                 :minQuantity, :maxQuantity, :priceIncrement, :sortOrder)
                            """)
                    .param("versionId", versionId)
                    .param("productId", request.productId())
                    .param("variantId", request.variantId())
                    .param("accessoryId", request.accessoryId())
                    .param("selectionGroup", normalizeCode(request.selectionGroupCode()))
                    .param("required", Boolean.TRUE.equals(request.required()))
                    .param("defaultValue", Boolean.TRUE.equals(request.defaultValue()))
                    .param("minQuantity", request.minQuantity())
                    .param("maxQuantity", request.maxQuantity())
                    .param("priceIncrement", request.priceIncrementCents())
                    .param("sortOrder", Objects.requireNonNullElse(request.sortOrder(), 0))
                    .update();
        } catch (DuplicateKeyException ex) {
            throw conflict("accessory is already bound to this product or variant", ex);
        }
        long id = latestId("catalog_product_accessory_binding_v2", "binding_id", versionId);
        Map<String, Object> after = entitySnapshot("ACCESSORY_BINDING", id);
        audit(versionId, "PRODUCT_ACCESSORY_BINDING", id, "BIND", null, after, identity.userId(), null);
        return after;
    }

    @Transactional
    public Map<String, Object> createAlias(
            long versionId, CreateAliasRequest request, BootstrapIdentity identity) {
        requireManage(identity);
        requireDraft(versionId);
        requireCanonicalTarget(request.canonicalType(), request.canonicalId(), versionId);
        String alias = request.aliasText().trim();
        try {
            jdbcClient.sql("""
                            INSERT INTO catalog_alias_v2
                                (config_version_id, canonical_type, canonical_id,
                                 alias_text, normalized_alias)
                            VALUES
                                (:versionId, :canonicalType, :canonicalId,
                                 :aliasText, :normalizedAlias)
                            """)
                    .param("versionId", versionId)
                    .param("canonicalType", request.canonicalType())
                    .param("canonicalId", request.canonicalId())
                    .param("aliasText", alias)
                    .param("normalizedAlias", normalizeAlias(alias))
                    .update();
        } catch (DuplicateKeyException ex) {
            throw conflict("alias already exists in this draft", ex);
        }
        long id = latestId("catalog_alias_v2", "alias_id", versionId);
        Map<String, Object> after = entitySnapshot("ALIAS", id);
        audit(versionId, "ALIAS", id, "CREATE", null, after, identity.userId(), null);
        return after;
    }

    @Transactional
    public Map<String, Object> createRule(
            long versionId, CreateCatalogRuleRequest request, BootstrapIdentity identity) {
        requireManage(identity);
        requireDraft(versionId);
        if (request.productId() != null) {
            requireProduct(request.productId(), versionId);
            requireVariant(request.variantId(), request.productId(), versionId);
        } else if (request.variantId() != null) {
            throw badRequest("variant_id requires product_id");
        }
        ruleSchemaValidator.validate(request.ruleType(), request.ruleSchema());
        try {
            jdbcClient.sql("""
                            INSERT INTO catalog_rule_v2
                                (config_version_id, product_id, variant_id,
                                 rule_type, rule_code, rule_schema_json, sort_order)
                            VALUES
                                (:versionId, :productId, :variantId,
                                 :ruleType, :ruleCode, CAST(:ruleSchema AS JSON), :sortOrder)
                            """)
                    .param("versionId", versionId)
                    .param("productId", request.productId())
                    .param("variantId", request.variantId())
                    .param("ruleType", request.ruleType())
                    .param("ruleCode", normalizeCode(request.ruleCode()))
                    .param("ruleSchema", json(request.ruleSchema()))
                    .param("sortOrder", Objects.requireNonNullElse(request.sortOrder(), 0))
                    .update();
        } catch (DuplicateKeyException ex) {
            throw conflict("rule code already exists in this draft", ex);
        }
        long id = latestId("catalog_rule_v2", "rule_id", versionId);
        Map<String, Object> after = entitySnapshot("RULE", id);
        audit(versionId, "RULE", id, "CREATE", null, after, identity.userId(), null);
        return after;
    }

    @Transactional
    public Map<String, Object> updateNamedEntity(
            String entityType,
            long entityId,
            UpdateNamedCatalogEntityRequest request,
            BootstrapIdentity identity) {
        requireManage(identity);
        EntityMetadata metadata = namedEntityMetadata(entityType);
        Map<String, Object> before = entitySnapshot(metadata.entityType(), entityId);
        long versionId = ((Number) before.get("config_version_id")).longValue();
        requireDraft(versionId);
        int updated = jdbcClient.sql("""
                        UPDATE %s
                        SET display_name = :displayName,
                            sort_order = :sortOrder,
                            status = :status,
                            lock_version = lock_version + 1
                        WHERE %s = :entityId
                          AND lock_version = :lockVersion
                        """.formatted(metadata.tableName(), metadata.idColumn()))
                .param("displayName", request.displayName().trim())
                .param("sortOrder", Objects.requireNonNullElse(request.sortOrder(), 0))
                .param("status", request.status())
                .param("entityId", entityId)
                .param("lockVersion", request.lockVersion())
                .update();
        if (updated == 0) {
            throw conflict("catalog entity changed concurrently; refresh and retry", null);
        }
        Map<String, Object> after = entitySnapshot(metadata.entityType(), entityId);
        audit(versionId, metadata.auditType(), entityId, action(before, after), before, after, identity.userId(), null);
        return after;
    }

    @Transactional
    public Map<String, Object> updateProduct(
            long productId, UpdateProductRequest request, BootstrapIdentity identity) {
        requireManage(identity);
        Map<String, Object> before = entitySnapshot("PRODUCT", productId);
        long versionId = ((Number) before.get("config_version_id")).longValue();
        requireDraft(versionId);
        requireWorkflowType(request.workflowProductType());
        if ("PENDING_QUOTE".equals(request.pricingStatus()) && request.basePriceCents() != null) {
            throw badRequest("pending quote product cannot carry a base price");
        }
        if ("PRICED".equals(request.pricingStatus()) && request.basePriceCents() == null) {
            throw badRequest("priced product requires base_price_cents");
        }
        int updated = jdbcClient.sql("""
                        UPDATE catalog_product_v2
                        SET display_name = :displayName,
                            workflow_product_type = :workflowType,
                            tooth_rule_code = :toothRule,
                            pricing_status = :pricingStatus,
                            base_price_cents = :basePrice,
                            currency = :currency,
                            sort_order = :sortOrder,
                            status = :status,
                            lock_version = lock_version + 1
                        WHERE product_id = :productId
                          AND lock_version = :lockVersion
                        """)
                .param("displayName", request.displayName().trim())
                .param("workflowType", nullable(request.workflowProductType()))
                .param("toothRule", nullable(request.toothRuleCode()))
                .param("pricingStatus", request.pricingStatus())
                .param("basePrice", request.basePriceCents())
                .param("currency", request.currency())
                .param("sortOrder", Objects.requireNonNullElse(request.sortOrder(), 0))
                .param("status", request.status())
                .param("productId", productId)
                .param("lockVersion", request.lockVersion())
                .update();
        if (updated == 0) {
            throw conflict("product changed concurrently; refresh and retry", null);
        }
        Map<String, Object> after = entitySnapshot("PRODUCT", productId);
        audit(versionId, "PRODUCT", productId, action(before, after), before, after, identity.userId(), null);
        return after;
    }

    @Transactional
    public Map<String, Object> updateRule(
            long ruleId, UpdateCatalogRuleRequest request, BootstrapIdentity identity) {
        requireManage(identity);
        Map<String, Object> before = entitySnapshot("RULE", ruleId);
        long versionId = ((Number) before.get("config_version_id")).longValue();
        requireDraft(versionId);
        ruleSchemaValidator.validate(String.valueOf(before.get("rule_type")), request.ruleSchema());
        int updated = jdbcClient.sql("""
                        UPDATE catalog_rule_v2
                        SET rule_schema_json = CAST(:ruleSchema AS JSON),
                            sort_order = :sortOrder,
                            status = :status,
                            lock_version = lock_version + 1
                        WHERE rule_id = :ruleId
                          AND lock_version = :lockVersion
                        """)
                .param("ruleSchema", json(request.ruleSchema()))
                .param("sortOrder", Objects.requireNonNullElse(request.sortOrder(), 0))
                .param("status", request.status())
                .param("ruleId", ruleId)
                .param("lockVersion", request.lockVersion())
                .update();
        if (updated == 0) {
            throw conflict("rule changed concurrently; refresh and retry", null);
        }
        Map<String, Object> after = entitySnapshot("RULE", ruleId);
        audit(versionId, "RULE", ruleId, action(before, after), before, after, identity.userId(), null);
        return after;
    }

    @Transactional
    public Map<String, Object> updateMaterialBinding(
            long bindingId,
            UpdateMaterialBindingRequest request,
            BootstrapIdentity identity) {
        requireManage(identity);
        validateQuantityRange(request.minQuantity(), request.maxQuantity());
        Map<String, Object> before = entitySnapshot("MATERIAL_BINDING", bindingId);
        long versionId = ((Number) before.get("config_version_id")).longValue();
        requireDraft(versionId);
        int updated = jdbcClient.sql("""
                        UPDATE catalog_product_material_binding_v2
                        SET selection_group_code = :selectionGroup,
                            required_flag = :required,
                            selection_mode = :selectionMode,
                            default_flag = :defaultValue,
                            min_quantity = :minQuantity,
                            max_quantity = :maxQuantity,
                            price_increment_cents = :priceIncrement,
                            sort_order = :sortOrder,
                            status = :status,
                            lock_version = lock_version + 1
                        WHERE binding_id = :bindingId
                          AND lock_version = :lockVersion
                        """)
                .param("selectionGroup", request.selectionGroupCode())
                .param("required", Boolean.TRUE.equals(request.required()) ? 1 : 0)
                .param("selectionMode", request.selectionMode())
                .param("defaultValue", Boolean.TRUE.equals(request.defaultValue()) ? 1 : 0)
                .param("minQuantity", request.minQuantity())
                .param("maxQuantity", request.maxQuantity())
                .param("priceIncrement", request.priceIncrementCents())
                .param("sortOrder", Objects.requireNonNullElse(request.sortOrder(), 0))
                .param("status", request.status())
                .param("bindingId", bindingId)
                .param("lockVersion", request.lockVersion())
                .update();
        if (updated == 0) {
            throw conflict("material binding changed concurrently; refresh and retry", null);
        }
        Map<String, Object> after = entitySnapshot("MATERIAL_BINDING", bindingId);
        audit(versionId, "PRODUCT_MATERIAL_BINDING", bindingId, action(before, after), before, after, identity.userId(), null);
        return after;
    }

    @Transactional
    public Map<String, Object> updateAccessoryBinding(
            long bindingId,
            UpdateAccessoryBindingRequest request,
            BootstrapIdentity identity) {
        requireManage(identity);
        validateQuantityRange(request.minQuantity(), request.maxQuantity());
        Map<String, Object> before = entitySnapshot("ACCESSORY_BINDING", bindingId);
        long versionId = ((Number) before.get("config_version_id")).longValue();
        requireDraft(versionId);
        int updated = jdbcClient.sql("""
                        UPDATE catalog_product_accessory_binding_v2
                        SET selection_group_code = :selectionGroup,
                            required_flag = :required,
                            default_flag = :defaultValue,
                            min_quantity = :minQuantity,
                            max_quantity = :maxQuantity,
                            price_increment_cents = :priceIncrement,
                            sort_order = :sortOrder,
                            status = :status,
                            lock_version = lock_version + 1
                        WHERE binding_id = :bindingId
                          AND lock_version = :lockVersion
                        """)
                .param("selectionGroup", request.selectionGroupCode())
                .param("required", Boolean.TRUE.equals(request.required()) ? 1 : 0)
                .param("defaultValue", Boolean.TRUE.equals(request.defaultValue()) ? 1 : 0)
                .param("minQuantity", request.minQuantity())
                .param("maxQuantity", request.maxQuantity())
                .param("priceIncrement", request.priceIncrementCents())
                .param("sortOrder", Objects.requireNonNullElse(request.sortOrder(), 0))
                .param("status", request.status())
                .param("bindingId", bindingId)
                .param("lockVersion", request.lockVersion())
                .update();
        if (updated == 0) {
            throw conflict("accessory binding changed concurrently; refresh and retry", null);
        }
        Map<String, Object> after = entitySnapshot("ACCESSORY_BINDING", bindingId);
        audit(versionId, "PRODUCT_ACCESSORY_BINDING", bindingId, action(before, after), before, after, identity.userId(), null);
        return after;
    }

    @Transactional
    public Map<String, Object> updateAlias(
            long aliasId,
            UpdateAliasRequest request,
            BootstrapIdentity identity) {
        requireManage(identity);
        Map<String, Object> before = entitySnapshot("ALIAS", aliasId);
        long versionId = ((Number) before.get("config_version_id")).longValue();
        requireDraft(versionId);
        String alias = request.aliasText().trim();
        int updated;
        try {
            updated = jdbcClient.sql("""
                            UPDATE catalog_alias_v2
                            SET alias_text = :alias,
                                normalized_alias = LOWER(TRIM(:alias)),
                                status = :status,
                                lock_version = lock_version + 1
                            WHERE alias_id = :aliasId
                              AND lock_version = :lockVersion
                            """)
                    .param("alias", alias)
                    .param("status", request.status())
                    .param("aliasId", aliasId)
                    .param("lockVersion", request.lockVersion())
                    .update();
        } catch (DuplicateKeyException ex) {
            throw conflict("alias already exists in this configuration version", ex);
        }
        if (updated == 0) {
            throw conflict("alias changed concurrently; refresh and retry", null);
        }
        Map<String, Object> after = entitySnapshot("ALIAS", aliasId);
        audit(versionId, "ALIAS", aliasId, action(before, after), before, after, identity.userId(), null);
        return after;
    }

    @Transactional
    public void deleteDraftEntity(
            String entityType, long entityId, BootstrapIdentity identity) {
        requireManage(identity);
        EntityMetadata metadata = deletableEntityMetadata(entityType);
        Map<String, Object> before = entitySnapshot(metadata.entityType(), entityId);
        long versionId = ((Number) before.get("config_version_id")).longValue();
        requireDraft(versionId);
        if (snapshotReferences(versionId) > 0) {
            throw conflict("referenced catalog entities cannot be physically deleted; deactivate instead", null);
        }
        try {
            int deleted = jdbcClient.sql("""
                            DELETE FROM %s
                            WHERE %s = :entityId
                            """.formatted(metadata.tableName(), metadata.idColumn()))
                    .param("entityId", entityId)
                    .update();
            if (deleted == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "catalog entity not found");
            }
        } catch (DataIntegrityViolationException ex) {
            throw conflict("referenced catalog entities cannot be physically deleted; deactivate instead", ex);
        }
        audit(versionId, metadata.auditType(), entityId, "DELETE_DRAFT", before, null, identity.userId(), null);
    }

    private void copyVersion(long sourceVersionId, long targetVersionId) {
        update("""
                INSERT INTO catalog_category_v2
                    (config_version_id, category_code, display_name, sort_order, status)
                SELECT :target, category_code, display_name, sort_order, status
                FROM catalog_category_v2 WHERE config_version_id = :source
                """, sourceVersionId, targetVersionId);
        update("""
                INSERT INTO catalog_product_v2
                    (config_version_id, category_id, product_code, display_name,
                     workflow_product_type, tooth_rule_code, pricing_status,
                     base_price_cents, currency, sort_order, status)
                SELECT :target, new_category.category_id, old_product.product_code,
                       old_product.display_name, old_product.workflow_product_type,
                       old_product.tooth_rule_code, old_product.pricing_status,
                       old_product.base_price_cents, old_product.currency,
                       old_product.sort_order, old_product.status
                FROM catalog_product_v2 old_product
                JOIN catalog_category_v2 old_category
                  ON old_category.category_id = old_product.category_id
                JOIN catalog_category_v2 new_category
                  ON new_category.config_version_id = :target
                 AND new_category.category_code = old_category.category_code
                WHERE old_product.config_version_id = :source
                """, sourceVersionId, targetVersionId);
        update("""
                INSERT INTO catalog_product_variant_v2
                    (config_version_id, product_id, variant_code,
                     display_name, attributes_json, sort_order, status)
                SELECT :target, new_product.product_id, old_variant.variant_code,
                       old_variant.display_name, old_variant.attributes_json,
                       old_variant.sort_order, old_variant.status
                FROM catalog_product_variant_v2 old_variant
                JOIN catalog_product_v2 old_product
                  ON old_product.product_id = old_variant.product_id
                JOIN catalog_product_v2 new_product
                  ON new_product.config_version_id = :target
                 AND new_product.product_code = old_product.product_code
                WHERE old_variant.config_version_id = :source
                """, sourceVersionId, targetVersionId);
        update("""
                INSERT INTO catalog_material_v2
                    (config_version_id, material_code, display_name,
                     material_family, brand_name, specification, sort_order, status)
                SELECT :target, material_code, display_name, material_family,
                       brand_name, specification, sort_order, status
                FROM catalog_material_v2 WHERE config_version_id = :source
                """, sourceVersionId, targetVersionId);
        update("""
                INSERT INTO catalog_material_color_v2
                    (material_id, semantic_type, color_code, display_name, sort_order, status)
                SELECT new_material.material_id, old_color.semantic_type,
                       old_color.color_code, old_color.display_name,
                       old_color.sort_order, old_color.status
                FROM catalog_material_color_v2 old_color
                JOIN catalog_material_v2 old_material
                  ON old_material.material_id = old_color.material_id
                JOIN catalog_material_v2 new_material
                  ON new_material.config_version_id = :target
                 AND new_material.material_code = old_material.material_code
                WHERE old_material.config_version_id = :source
                """, sourceVersionId, targetVersionId);
        update("""
                INSERT INTO catalog_accessory_v2
                    (config_version_id, accessory_code, display_name,
                     quantity_supported, sort_order, status)
                SELECT :target, accessory_code, display_name,
                       quantity_supported, sort_order, status
                FROM catalog_accessory_v2 WHERE config_version_id = :source
                """, sourceVersionId, targetVersionId);
        update("""
                INSERT INTO catalog_product_material_binding_v2
                    (config_version_id, product_id, variant_id, material_id,
                     selection_group_code, required_flag, selection_mode, default_flag,
                     min_quantity, max_quantity, applicable_tooth_rule_json,
                     price_increment_cents, time_adjustment_minutes, sort_order, status)
                SELECT :target, new_product.product_id, new_variant.variant_id,
                       new_material.material_id, old_binding.selection_group_code,
                       old_binding.required_flag, old_binding.selection_mode,
                       old_binding.default_flag, old_binding.min_quantity,
                       old_binding.max_quantity, old_binding.applicable_tooth_rule_json,
                       old_binding.price_increment_cents, old_binding.time_adjustment_minutes,
                       old_binding.sort_order, old_binding.status
                FROM catalog_product_material_binding_v2 old_binding
                JOIN catalog_product_v2 old_product
                  ON old_product.product_id = old_binding.product_id
                JOIN catalog_product_v2 new_product
                  ON new_product.config_version_id = :target
                 AND new_product.product_code = old_product.product_code
                JOIN catalog_material_v2 old_material
                  ON old_material.material_id = old_binding.material_id
                JOIN catalog_material_v2 new_material
                  ON new_material.config_version_id = :target
                 AND new_material.material_code = old_material.material_code
                LEFT JOIN catalog_product_variant_v2 old_variant
                  ON old_variant.variant_id = old_binding.variant_id
                LEFT JOIN catalog_product_variant_v2 new_variant
                  ON new_variant.config_version_id = :target
                 AND new_variant.variant_code = old_variant.variant_code
                WHERE old_binding.config_version_id = :source
                """, sourceVersionId, targetVersionId);
        update("""
                INSERT INTO catalog_product_accessory_binding_v2
                    (config_version_id, product_id, variant_id, accessory_id,
                     selection_group_code, required_flag, default_flag,
                     min_quantity, max_quantity, applicable_tooth_rule_json,
                     price_increment_cents, time_adjustment_minutes, sort_order, status)
                SELECT :target, new_product.product_id, new_variant.variant_id,
                       new_accessory.accessory_id, old_binding.selection_group_code,
                       old_binding.required_flag, old_binding.default_flag,
                       old_binding.min_quantity, old_binding.max_quantity,
                       old_binding.applicable_tooth_rule_json,
                       old_binding.price_increment_cents, old_binding.time_adjustment_minutes,
                       old_binding.sort_order, old_binding.status
                FROM catalog_product_accessory_binding_v2 old_binding
                JOIN catalog_product_v2 old_product
                  ON old_product.product_id = old_binding.product_id
                JOIN catalog_product_v2 new_product
                  ON new_product.config_version_id = :target
                 AND new_product.product_code = old_product.product_code
                JOIN catalog_accessory_v2 old_accessory
                  ON old_accessory.accessory_id = old_binding.accessory_id
                JOIN catalog_accessory_v2 new_accessory
                  ON new_accessory.config_version_id = :target
                 AND new_accessory.accessory_code = old_accessory.accessory_code
                LEFT JOIN catalog_product_variant_v2 old_variant
                  ON old_variant.variant_id = old_binding.variant_id
                LEFT JOIN catalog_product_variant_v2 new_variant
                  ON new_variant.config_version_id = :target
                 AND new_variant.variant_code = old_variant.variant_code
                WHERE old_binding.config_version_id = :source
                """, sourceVersionId, targetVersionId);
        update("""
                INSERT INTO catalog_rule_v2
                    (config_version_id, product_id, variant_id, rule_type,
                     rule_code, rule_schema_json, sort_order, status)
                SELECT :target, new_product.product_id, new_variant.variant_id,
                       old_rule.rule_type, old_rule.rule_code,
                       old_rule.rule_schema_json, old_rule.sort_order, old_rule.status
                FROM catalog_rule_v2 old_rule
                LEFT JOIN catalog_product_v2 old_product
                  ON old_product.product_id = old_rule.product_id
                LEFT JOIN catalog_product_v2 new_product
                  ON new_product.config_version_id = :target
                 AND new_product.product_code = old_product.product_code
                LEFT JOIN catalog_product_variant_v2 old_variant
                  ON old_variant.variant_id = old_rule.variant_id
                LEFT JOIN catalog_product_variant_v2 new_variant
                  ON new_variant.config_version_id = :target
                 AND new_variant.variant_code = old_variant.variant_code
                WHERE old_rule.config_version_id = :source
                """, sourceVersionId, targetVersionId);
        copyAliases(sourceVersionId, targetVersionId, "PRODUCT",
                "catalog_product_v2", "product_id", "product_code");
        copyAliases(sourceVersionId, targetVersionId, "PRODUCT_VARIANT",
                "catalog_product_variant_v2", "variant_id", "variant_code");
        copyAliases(sourceVersionId, targetVersionId, "MATERIAL",
                "catalog_material_v2", "material_id", "material_code");
        copyAliases(sourceVersionId, targetVersionId, "ACCESSORY",
                "catalog_accessory_v2", "accessory_id", "accessory_code");
    }

    private void copyAliases(
            long sourceVersionId,
            long targetVersionId,
            String canonicalType,
            String table,
            String idColumn,
            String codeColumn) {
        update("""
                INSERT INTO catalog_alias_v2
                    (config_version_id, canonical_type, canonical_id,
                     alias_text, normalized_alias, status)
                SELECT :target, old_alias.canonical_type, new_entity.%s,
                       old_alias.alias_text, old_alias.normalized_alias, old_alias.status
                FROM catalog_alias_v2 old_alias
                JOIN %s old_entity ON old_entity.%s = old_alias.canonical_id
                JOIN %s new_entity
                  ON new_entity.config_version_id = :target
                 AND new_entity.%s = old_entity.%s
                WHERE old_alias.config_version_id = :source
                  AND old_alias.canonical_type = '%s'
                """.formatted(idColumn, table, idColumn, table, codeColumn, codeColumn, canonicalType),
                sourceVersionId,
                targetVersionId);
    }

    private void update(String sql, long sourceVersionId, long targetVersionId) {
        jdbcClient.sql(sql)
                .param("source", sourceVersionId)
                .param("target", targetVersionId)
                .update();
    }

    private Map<String, Object> entitySnapshot(String entityType, long entityId) {
        EntityMetadata metadata = metadata(entityType);
        String sql = switch (metadata.entityType()) {
            case "MATERIAL_COLOR" -> """
                    SELECT color.material_color_id, material.config_version_id,
                           color.material_id, color.semantic_type, color.color_code,
                           color.display_name, color.sort_order, color.status, color.lock_version
                    FROM catalog_material_color_v2 color
                    JOIN catalog_material_v2 material ON material.material_id = color.material_id
                    WHERE color.material_color_id = :entityId
                    """;
            default -> "SELECT * FROM " + metadata.tableName()
                    + " WHERE " + metadata.idColumn() + " = :entityId";
        };
        try {
            return jdbcClient.sql(sql)
                    .param("entityId", entityId)
                    .query((rs, rowNum) -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        var meta = rs.getMetaData();
                        for (int index = 1; index <= meta.getColumnCount(); index++) {
                            row.put(meta.getColumnLabel(index), rs.getObject(index));
                        }
                        return row;
                    })
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "catalog entity not found", ex);
        }
    }

    private EntityMetadata metadata(String entityType) {
        return switch (entityType.trim().toUpperCase(Locale.ROOT)) {
            case "CATEGORY" -> new EntityMetadata("CATEGORY", "catalog_category_v2", "category_id", "CATEGORY");
            case "PRODUCT" -> new EntityMetadata("PRODUCT", "catalog_product_v2", "product_id", "PRODUCT");
            case "VARIANT" -> new EntityMetadata("VARIANT", "catalog_product_variant_v2", "variant_id", "PRODUCT_VARIANT");
            case "MATERIAL" -> new EntityMetadata("MATERIAL", "catalog_material_v2", "material_id", "MATERIAL");
            case "ACCESSORY" -> new EntityMetadata("ACCESSORY", "catalog_accessory_v2", "accessory_id", "ACCESSORY");
            case "MATERIAL_COLOR" -> new EntityMetadata("MATERIAL_COLOR", "catalog_material_color_v2", "material_color_id", "MATERIAL_COLOR");
            case "ALIAS" -> new EntityMetadata("ALIAS", "catalog_alias_v2", "alias_id", "ALIAS");
            case "RULE" -> new EntityMetadata("RULE", "catalog_rule_v2", "rule_id", "RULE");
            case "MATERIAL_BINDING" -> new EntityMetadata("MATERIAL_BINDING", "catalog_product_material_binding_v2", "binding_id", "PRODUCT_MATERIAL_BINDING");
            case "ACCESSORY_BINDING" -> new EntityMetadata("ACCESSORY_BINDING", "catalog_product_accessory_binding_v2", "binding_id", "PRODUCT_ACCESSORY_BINDING");
            default -> throw badRequest("unsupported catalog entity type");
        };
    }

    private EntityMetadata namedEntityMetadata(String entityType) {
        EntityMetadata metadata = metadata(entityType);
        if (!java.util.Set.of("CATEGORY", "VARIANT", "ACCESSORY", "MATERIAL_COLOR")
                .contains(metadata.entityType())) {
            throw badRequest("this entity type does not use the named entity update contract");
        }
        return metadata;
    }

    private EntityMetadata deletableEntityMetadata(String entityType) {
        EntityMetadata metadata = metadata(entityType);
        if ("MATERIAL".equals(metadata.entityType())) {
            throw badRequest("use the material-specific safe delete endpoint");
        }
        return metadata;
    }

    private void requireCanonicalTarget(String type, long id, long versionId) {
        EntityMetadata metadata = switch (type) {
            case "PRODUCT" -> metadata("PRODUCT");
            case "PRODUCT_VARIANT" -> metadata("VARIANT");
            case "MATERIAL" -> metadata("MATERIAL");
            case "ACCESSORY" -> metadata("ACCESSORY");
            default -> throw badRequest("unsupported canonical type");
        };
        long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM %s
                        WHERE %s = :entityId
                          AND config_version_id = :versionId
                        """.formatted(metadata.tableName(), metadata.idColumn()))
                .param("entityId", id)
                .param("versionId", versionId)
                .query(Long.class)
                .single();
        if (count == 0) {
            throw badRequest("alias target must belong to this draft version");
        }
    }

    private void requireProduct(long productId, long versionId) {
        requireVersionEntity("catalog_product_v2", "product_id", productId, versionId, "product");
    }

    private void requireMaterial(long materialId, long versionId) {
        requireVersionEntity("catalog_material_v2", "material_id", materialId, versionId, "material");
    }

    private void requireAccessory(long accessoryId, long versionId) {
        requireVersionEntity("catalog_accessory_v2", "accessory_id", accessoryId, versionId, "accessory");
    }

    private void requireVariant(Long variantId, long productId, long versionId) {
        if (variantId == null) {
            return;
        }
        long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM catalog_product_variant_v2
                        WHERE variant_id = :variantId
                          AND product_id = :productId
                          AND config_version_id = :versionId
                        """)
                .param("variantId", variantId)
                .param("productId", productId)
                .param("versionId", versionId)
                .query(Long.class)
                .single();
        if (count == 0) {
            throw badRequest("variant must belong to the selected product and draft version");
        }
    }

    private void requireVersionEntity(
            String table, String idColumn, long id, long versionId, String label) {
        long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM %s
                        WHERE %s = :entityId
                          AND config_version_id = :versionId
                        """.formatted(table, idColumn))
                .param("entityId", id)
                .param("versionId", versionId)
                .query(Long.class)
                .single();
        if (count == 0) {
            throw badRequest(label + " must belong to this draft version");
        }
    }

    private void requireWorkflowType(String productType) {
        String normalized = nullable(productType);
        if (normalized == null) {
            return;
        }
        long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM workflow_chain
                        WHERE product_type = :productType
                          AND status = 1
                        """)
                .param("productType", normalized.toUpperCase(Locale.ROOT))
                .query(Long.class)
                .single();
        if (count == 0) {
            throw badRequest("workflow_product_type must reference an active workflow chain");
        }
    }

    private void requireDraft(long versionId) {
        if (!"DRAFT".equals(loadVersion(versionId).publicationStatus())) {
            throw conflict("published catalog versions are immutable", null);
        }
    }

    private void requireVersionExists(long versionId) {
        loadVersion(versionId);
    }

    private CatalogVersionResponse loadVersion(long versionId) {
        try {
            return jdbcClient.sql("""
                            SELECT config_version_id, version_no, version_name,
                                   publication_status, effective_at, lock_version
                            FROM catalog_config_version
                            WHERE config_version_id = :versionId
                            """)
                    .param("versionId", versionId)
                    .query((rs, rowNum) -> new CatalogVersionResponse(
                            rs.getLong("config_version_id"),
                            rs.getInt("version_no"),
                            rs.getString("version_name"),
                            rs.getString("publication_status"),
                            rs.getObject("effective_at", java.time.LocalDateTime.class),
                            rs.getInt("lock_version")))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "catalog version not found", ex);
        }
    }

    private long latestId(String table, String idColumn, long versionId) {
        return jdbcClient.sql("""
                        SELECT %s
                        FROM %s
                        WHERE config_version_id = :versionId
                        ORDER BY %s DESC
                        LIMIT 1
                        """.formatted(idColumn, table, idColumn))
                .param("versionId", versionId)
                .query(Long.class)
                .single();
    }

    private long snapshotReferences(long versionId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_catalog_snapshot
                        WHERE config_version_id = :versionId
                        """)
                .param("versionId", versionId)
                .query(Long.class)
                .single();
    }

    private void validateQuantityRange(Integer min, Integer max) {
        if (min != null && max != null && min > max) {
            throw badRequest("min_quantity cannot exceed max_quantity");
        }
    }

    private String action(Map<String, Object> before, Map<String, Object> after) {
        String oldStatus = Objects.toString(before.get("status"), "");
        String newStatus = Objects.toString(after.get("status"), "");
        if (!oldStatus.equals(newStatus)) {
            return "INACTIVE".equals(newStatus) ? "DEACTIVATE" : "RESTORE";
        }
        if (!Objects.equals(before.get("sort_order"), after.get("sort_order"))) {
            return "SORT";
        }
        if (!Objects.equals(before.get("base_price_cents"), after.get("base_price_cents"))
                || !Objects.equals(before.get("pricing_status"), after.get("pricing_status"))) {
            return "PRICE_CHANGE";
        }
        return "UPDATE";
    }

    private void audit(
            Long versionId,
            String entityType,
            Long entityId,
            String action,
            Object before,
            Object after,
            Long operatorUserId,
            String reason) {
        jdbcClient.sql("""
                        INSERT INTO catalog_change_audit
                            (config_version_id, entity_type, entity_id, action_type,
                             before_value, after_value, operator_user_id, reason)
                        VALUES
                            (:versionId, :entityType, :entityId, :action,
                             CAST(:beforeValue AS JSON), CAST(:afterValue AS JSON),
                             :operatorUserId, :reason)
                        """)
                .param("versionId", versionId)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("action", action)
                .param("beforeValue", jsonOrNull(before))
                .param("afterValue", jsonOrNull(after))
                .param("operatorUserId", operatorUserId)
                .param("reason", reason)
                .update();
    }

    private void requireManage(BootstrapIdentity identity) {
        if (identity.role() == UserRole.ADMIN || identity.hasPermission("catalog:manage")) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "catalog:manage permission is required");
    }

    private String normalizeCode(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeAlias(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String json(JsonNode value) {
        return jsonOrNull(value);
    }

    private String jsonOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize catalog value", ex);
        }
    }

    private ResponseStatusException conflict(String message, Exception cause) {
        return cause == null
                ? new ResponseStatusException(HttpStatus.CONFLICT, message)
                : new ResponseStatusException(HttpStatus.CONFLICT, message, cause);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record EntityMetadata(
            String entityType, String tableName, String idColumn, String auditType) {
    }
}
