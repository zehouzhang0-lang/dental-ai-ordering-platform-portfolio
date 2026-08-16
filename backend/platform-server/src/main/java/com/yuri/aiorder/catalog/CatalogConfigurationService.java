package com.yuri.aiorder.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CatalogVersionResponse;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CreateCategoryRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CreateMaterialBindingRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CreateMaterialRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CreateProductRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.MaterialResponse;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.PublishCatalogRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.UpdateMaterialRequest;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CatalogConfigurationService {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final CatalogRuleSchemaValidator ruleSchemaValidator;

    public CatalogConfigurationService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            CatalogRuleSchemaValidator ruleSchemaValidator) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.ruleSchemaValidator = ruleSchemaValidator;
    }

    @Transactional(readOnly = true)
    public List<CatalogVersionResponse> listVersions(BootstrapIdentity identity) {
        requireManage(identity);
        return jdbcClient.sql("""
                        SELECT config_version_id, version_no, version_name,
                               publication_status, effective_at, lock_version
                        FROM catalog_config_version
                        ORDER BY version_no DESC
                        """)
                .query((rs, rowNum) -> new CatalogVersionResponse(
                        rs.getLong("config_version_id"),
                        rs.getInt("version_no"),
                        rs.getString("version_name"),
                        rs.getString("publication_status"),
                        rs.getObject("effective_at", LocalDateTime.class),
                        rs.getInt("lock_version")))
                .list();
    }

    @Transactional
    public Map<String, Object> createCategory(
            long versionId, CreateCategoryRequest request, BootstrapIdentity identity) {
        requireManage(identity);
        requireDraftVersion(versionId);
        String code = normalizeCode(request.categoryCode());
        try {
            jdbcClient.sql("""
                            INSERT INTO catalog_category_v2
                                (config_version_id, category_code, display_name, sort_order)
                            VALUES (:versionId, :code, :name, :sortOrder)
                            """)
                    .param("versionId", versionId)
                    .param("code", code)
                    .param("name", request.displayName().trim())
                    .param("sortOrder", Objects.requireNonNullElse(request.sortOrder(), 0))
                    .update();
        } catch (DuplicateKeyException ex) {
            throw conflict("category code already exists in this draft", ex);
        }
        long id = lastId("catalog_category_v2", "category_id", "config_version_id", versionId);
        Map<String, Object> after = categorySnapshot(id);
        audit(versionId, "CATEGORY", id, "CREATE", null, after, identity.userId(), null);
        return after;
    }

    @Transactional
    public Map<String, Object> createProduct(
            long versionId, CreateProductRequest request, BootstrapIdentity identity) {
        requireManage(identity);
        requireDraftVersion(versionId);
        requireCategoryInVersion(request.categoryId(), versionId);
        String code = normalizeCode(request.productCode());
        String workflowProductType = normalizeNullable(request.workflowProductType());
        requireKnownWorkflowProductType(workflowProductType);
        try {
            jdbcClient.sql("""
                            INSERT INTO catalog_product_v2
                                (config_version_id, category_id, product_code, display_name,
                                 workflow_product_type, tooth_rule_code, sort_order)
                            VALUES
                                (:versionId, :categoryId, :code, :name,
                                 :workflowType, :toothRuleCode, :sortOrder)
                            """)
                    .param("versionId", versionId)
                    .param("categoryId", request.categoryId())
                    .param("code", code)
                    .param("name", request.displayName().trim())
                    .param("workflowType", workflowProductType)
                    .param("toothRuleCode", normalizeNullable(request.toothRuleCode()))
                    .param("sortOrder", Objects.requireNonNullElse(request.sortOrder(), 0))
                    .update();
        } catch (DuplicateKeyException ex) {
            throw conflict("product code already exists in this draft", ex);
        }
        long id = lastId("catalog_product_v2", "product_id", "config_version_id", versionId);
        Map<String, Object> after = productSnapshot(id);
        audit(versionId, "PRODUCT", id, "CREATE", null, after, identity.userId(), null);
        return after;
    }

    @Transactional
    public MaterialResponse createMaterial(
            long versionId, CreateMaterialRequest request, BootstrapIdentity identity) {
        requireManage(identity);
        requireDraftVersion(versionId);
        String code = normalizeCode(request.materialCode());
        try {
            jdbcClient.sql("""
                            INSERT INTO catalog_material_v2
                                (config_version_id, material_code, display_name,
                                 material_family, brand_name, specification, sort_order)
                            VALUES
                                (:versionId, :code, :name,
                                 :family, :brand, :specification, :sortOrder)
                            """)
                    .param("versionId", versionId)
                    .param("code", code)
                    .param("name", request.displayName().trim())
                    .param("family", normalizeNullable(request.materialFamily()))
                    .param("brand", normalizeNullable(request.brandName()))
                    .param("specification", normalizeNullable(request.specification()))
                    .param("sortOrder", Objects.requireNonNullElse(request.sortOrder(), 0))
                    .update();
        } catch (DuplicateKeyException ex) {
            throw conflict("material code already exists in this draft", ex);
        }
        long id = lastId("catalog_material_v2", "material_id", "config_version_id", versionId);
        MaterialResponse after = loadMaterial(id);
        audit(versionId, "MATERIAL", id, "CREATE", null, after, identity.userId(), null);
        return after;
    }

    @Transactional
    public MaterialResponse updateMaterial(
            long materialId, UpdateMaterialRequest request, BootstrapIdentity identity) {
        requireManage(identity);
        MaterialResponse before = loadMaterial(materialId);
        requireDraftVersion(before.configVersionId());
        int updated = jdbcClient.sql("""
                        UPDATE catalog_material_v2
                        SET display_name = :name,
                            material_family = :family,
                            brand_name = :brand,
                            specification = :specification,
                            sort_order = :sortOrder,
                            status = :status,
                            lock_version = lock_version + 1
                        WHERE material_id = :materialId
                          AND lock_version = :lockVersion
                        """)
                .param("name", request.displayName().trim())
                .param("family", normalizeNullable(request.materialFamily()))
                .param("brand", normalizeNullable(request.brandName()))
                .param("specification", normalizeNullable(request.specification()))
                .param("sortOrder", Objects.requireNonNullElse(request.sortOrder(), 0))
                .param("status", request.status())
                .param("materialId", materialId)
                .param("lockVersion", request.lockVersion())
                .update();
        if (updated == 0) {
            throw conflict("material was updated by another operator; refresh and retry", null);
        }
        MaterialResponse after = loadMaterial(materialId);
        String action = !before.status().equals(after.status())
                ? ("INACTIVE".equals(after.status()) ? "DEACTIVATE" : "RESTORE")
                : "UPDATE";
        audit(
                before.configVersionId(),
                "MATERIAL",
                materialId,
                action,
                before,
                after,
                identity.userId(),
                null);
        return after;
    }

    @Transactional
    public void deleteMaterial(long materialId, BootstrapIdentity identity) {
        requireManage(identity);
        MaterialResponse before = loadMaterial(materialId);
        requireDraftVersion(before.configVersionId());
        long references = jdbcClient.sql("""
                        SELECT
                            (SELECT COUNT(*) FROM catalog_product_material_binding_v2
                             WHERE material_id = :materialId)
                          + (SELECT COUNT(*) FROM catalog_alias_v2
                             WHERE canonical_type = 'MATERIAL' AND canonical_id = :materialId)
                          + (SELECT COUNT(*) FROM order_catalog_snapshot
                             WHERE config_version_id = :versionId)
                        """)
                .param("materialId", materialId)
                .param("versionId", before.configVersionId())
                .query(Long.class)
                .single();
        if (references > 0) {
            throw conflict(
                    "published or referenced material cannot be physically deleted; deactivate it instead",
                    null);
        }
        jdbcClient.sql("DELETE FROM catalog_material_color_v2 WHERE material_id = :materialId")
                .param("materialId", materialId)
                .update();
        jdbcClient.sql("DELETE FROM catalog_material_v2 WHERE material_id = :materialId")
                .param("materialId", materialId)
                .update();
        audit(
                before.configVersionId(),
                "MATERIAL",
                materialId,
                "DELETE_DRAFT",
                before,
                null,
                identity.userId(),
                null);
    }

    @Transactional
    public Map<String, Object> bindMaterial(
            long versionId,
            CreateMaterialBindingRequest request,
            BootstrapIdentity identity) {
        requireManage(identity);
        requireDraftVersion(versionId);
        requireProductAndMaterialInVersion(
                request.productId(), request.variantId(), request.materialId(), versionId);
        if (request.minQuantity() != null
                && request.maxQuantity() != null
                && request.minQuantity() > request.maxQuantity()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "min_quantity cannot exceed max_quantity");
        }
        long duplicateCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM catalog_product_material_binding_v2
                        WHERE config_version_id = :versionId
                          AND product_id = :productId
                          AND variant_id <=> :variantId
                          AND material_id = :materialId
                        """)
                .param("versionId", versionId)
                .param("productId", request.productId())
                .param("variantId", request.variantId())
                .param("materialId", request.materialId())
                .query(Long.class)
                .single();
        if (duplicateCount > 0) {
            throw conflict("material is already bound to this product or variant", null);
        }
        jdbcClient.sql("""
                        INSERT INTO catalog_product_material_binding_v2
                            (config_version_id, product_id, variant_id, material_id,
                             selection_group_code,
                             required_flag, selection_mode, default_flag,
                             min_quantity, max_quantity, price_increment_cents, sort_order)
                        VALUES
                            (:versionId, :productId, :variantId, :materialId,
                             :selectionGroupCode,
                             :required, :selectionMode, :defaultValue,
                             :minQuantity, :maxQuantity, :priceIncrement, :sortOrder)
                        """)
                .param("versionId", versionId)
                .param("productId", request.productId())
                .param("variantId", request.variantId())
                .param("materialId", request.materialId())
                .param("selectionGroupCode", request.selectionGroupCode() == null
                        ? "MAIN_MATERIAL"
                        : normalizeCode(request.selectionGroupCode()))
                .param("required", Boolean.TRUE.equals(request.required()))
                .param("selectionMode", request.selectionMode())
                .param("defaultValue", Boolean.TRUE.equals(request.defaultValue()))
                .param("minQuantity", request.minQuantity())
                .param("maxQuantity", request.maxQuantity())
                .param("priceIncrement", request.priceIncrementCents())
                .param("sortOrder", Objects.requireNonNullElse(request.sortOrder(), 0))
                .update();
        long id = lastId(
                "catalog_product_material_binding_v2",
                "binding_id",
                "config_version_id",
                versionId);
        Map<String, Object> after = bindingSnapshot(id);
        audit(versionId, "PRODUCT_MATERIAL_BINDING", id, "BIND", null, after, identity.userId(), null);
        return after;
    }

    @Transactional
    public CatalogVersionResponse publish(
            long versionId, PublishCatalogRequest request, BootstrapIdentity identity) {
        requirePublish(identity);
        CatalogVersionResponse before = loadVersion(versionId);
        if (!"DRAFT".equals(before.publicationStatus())) {
            throw conflict("published catalog versions are immutable", null);
        }
        long productCount = count("catalog_product_v2", versionId);
        if (productCount == 0) {
            throw conflict("catalog draft must contain at least one product before publishing", null);
        }
        long incompleteProducts = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM catalog_product_v2 product
                        JOIN catalog_category_v2 category ON category.category_id = product.category_id
                        WHERE product.config_version_id = :versionId
                          AND (
                              category.config_version_id <> :versionId
                              OR product.workflow_product_type IS NULL
                              OR product.status <> 'ACTIVE'
                          )
                        """)
                .param("versionId", versionId)
                .query(Long.class)
                .single();
        if (incompleteProducts > 0) {
            throw conflict(
                    "catalog draft has incomplete products or missing workflow mappings",
                    null);
        }
        validateActiveRuleSchemas(versionId);
        LocalDateTime databaseNow = jdbcClient.sql("SELECT CURRENT_TIMESTAMP(3)")
                .query(LocalDateTime.class)
                .single();
        LocalDateTime effectiveAt = Objects.requireNonNullElse(request.effectiveAt(), databaseNow);
        int updated = jdbcClient.sql("""
                        UPDATE catalog_config_version
                        SET publication_status = 'ACTIVE',
                            effective_at = :effectiveAt,
                            published_at = CURRENT_TIMESTAMP(3),
                            published_by_user_id = :userId,
                            lock_version = lock_version + 1
                        WHERE config_version_id = :versionId
                          AND publication_status = 'DRAFT'
                          AND lock_version = :lockVersion
                        """)
                .param("effectiveAt", effectiveAt)
                .param("userId", identity.userId())
                .param("versionId", versionId)
                .param("lockVersion", request.lockVersion())
                .update();
        if (updated == 0) {
            throw conflict("catalog version changed concurrently; refresh and retry", null);
        }
        if (!effectiveAt.isAfter(databaseNow)) {
            jdbcClient.sql("""
                            UPDATE catalog_config_version
                            SET publication_status = 'INACTIVE',
                                lock_version = lock_version + 1
                            WHERE config_version_id <> :versionId
                              AND publication_status = 'ACTIVE'
                              AND (effective_at IS NULL OR effective_at <= :effectiveAt)
                            """)
                    .param("versionId", versionId)
                    .param("effectiveAt", effectiveAt)
                    .update();
        }
        CatalogVersionResponse after = loadVersion(versionId);
        audit(
                versionId,
                "CONFIG_VERSION",
                versionId,
                "PUBLISH",
                before,
                after,
                identity.userId(),
                request.reason());
        return after;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> preview(long versionId, BootstrapIdentity identity) {
        requireManage(identity);
        loadVersion(versionId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("version", loadVersion(versionId));
        response.put("categories", rows("""
                SELECT category_id, config_version_id, category_code, display_name,
                       sort_order, status, lock_version
                FROM catalog_category_v2
                WHERE config_version_id = :versionId
                ORDER BY sort_order, category_id
                """, versionId));
        response.put("products", rows("""
                SELECT product_id, config_version_id, category_id, product_code, display_name,
                       workflow_product_type, tooth_rule_code, pricing_status,
                       base_price_cents, currency, sort_order, status, lock_version
                FROM catalog_product_v2
                WHERE config_version_id = :versionId
                ORDER BY sort_order, product_id
                """, versionId));
        response.put("materials", jdbcClient.sql("""
                        SELECT material_id, config_version_id, material_code, display_name,
                               material_family, brand_name, specification,
                               sort_order, status, lock_version
                        FROM catalog_material_v2
                        WHERE config_version_id = :versionId
                        ORDER BY sort_order, material_id
                        """)
                .param("versionId", versionId)
                .query((rs, rowNum) -> mapMaterial(rs))
                .list());
        response.put("material_bindings", rows("""
                SELECT binding_id, product_id, variant_id, material_id, selection_group_code,
                       required_flag, selection_mode, default_flag,
                       min_quantity, max_quantity, price_increment_cents,
                       sort_order, status, lock_version
                FROM catalog_product_material_binding_v2
                WHERE config_version_id = :versionId
                ORDER BY sort_order, binding_id
                """, versionId));
        response.put("variants", rows("""
                SELECT variant_id, product_id, variant_code, display_name,
                       attributes_json, sort_order, status, lock_version
                FROM catalog_product_variant_v2
                WHERE config_version_id = :versionId
                ORDER BY sort_order, variant_id
                """, versionId));
        response.put("material_colors", rows("""
                SELECT color.material_color_id, color.material_id, color.semantic_type,
                       color.color_code, color.display_name, color.sort_order,
                       color.status, color.lock_version
                FROM catalog_material_color_v2 color
                JOIN catalog_material_v2 material ON material.material_id = color.material_id
                WHERE material.config_version_id = :versionId
                ORDER BY color.material_id, color.semantic_type, color.sort_order
                """, versionId));
        response.put("accessories", rows("""
                SELECT accessory_id, accessory_code, display_name, quantity_supported,
                       sort_order, status, lock_version
                FROM catalog_accessory_v2
                WHERE config_version_id = :versionId
                ORDER BY sort_order, accessory_id
                """, versionId));
        response.put("accessory_bindings", rows("""
                SELECT binding_id, product_id, variant_id, accessory_id,
                       selection_group_code, required_flag, default_flag,
                       min_quantity, max_quantity, price_increment_cents,
                       sort_order, status, lock_version
                FROM catalog_product_accessory_binding_v2
                WHERE config_version_id = :versionId
                ORDER BY sort_order, binding_id
                """, versionId));
        response.put("aliases", rows("""
                SELECT alias_id, canonical_type, canonical_id, alias_text,
                       normalized_alias, status, lock_version
                FROM catalog_alias_v2
                WHERE config_version_id = :versionId
                ORDER BY canonical_type, alias_id
                """, versionId));
        response.put("rules", rows("""
                SELECT rule_id, product_id, variant_id, rule_type, rule_code,
                       rule_schema_json, sort_order, status, lock_version
                FROM catalog_rule_v2
                WHERE config_version_id = :versionId
                ORDER BY rule_type, sort_order, rule_id
                """, versionId));
        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> activeDoctorConfiguration(BootstrapIdentity identity) {
        if (identity.role() != UserRole.DOCTOR
                && identity.role() != UserRole.ADMIN
                && identity.role() != UserRole.CS) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "catalog is not visible to this role");
        }
        Long versionId = jdbcClient.sql("""
                        SELECT config_version_id
                        FROM catalog_config_version
                        WHERE publication_status = 'ACTIVE'
                          AND effective_at <= CURRENT_TIMESTAMP(3)
                        ORDER BY effective_at DESC, version_no DESC
                        LIMIT 1
                        """)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (versionId == null) {
            return Map.of("publication_status", "NOT_PUBLISHED", "products", List.of());
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("publication_status", "ACTIVE");
        response.put("config_version_id", versionId);
        response.put("categories", rows("""
                SELECT category_id, category_code, display_name, sort_order
                FROM catalog_category_v2
                WHERE config_version_id = :versionId
                  AND status = 'ACTIVE'
                ORDER BY sort_order, category_id
                """, versionId));
        response.put("products", rows("""
                SELECT product.product_id, product.product_code, product.display_name,
                       product.workflow_product_type, product.tooth_rule_code,
                       product.pricing_status, product.base_price_cents, product.currency,
                       category.category_code, category.display_name AS category_name
                FROM catalog_product_v2 product
                JOIN catalog_category_v2 category ON category.category_id = product.category_id
                WHERE product.config_version_id = :versionId
                  AND product.status = 'ACTIVE'
                  AND category.status = 'ACTIVE'
                ORDER BY category.sort_order, product.sort_order, product.product_id
                """, versionId));
        response.put("variants", rows("""
                SELECT variant_id, product_id, variant_code, display_name,
                       attributes_json, sort_order
                FROM catalog_product_variant_v2
                WHERE config_version_id = :versionId
                  AND status = 'ACTIVE'
                ORDER BY product_id, sort_order, variant_id
                """, versionId));
        response.put("materials", rows("""
                SELECT binding.product_id, binding.variant_id, material.material_id,
                       material.material_code, material.display_name,
                       material.material_family, material.brand_name, material.specification,
                       binding.selection_group_code,
                       binding.required_flag, binding.selection_mode, binding.default_flag,
                       binding.min_quantity, binding.max_quantity,
                       binding.price_increment_cents, binding.sort_order
                FROM catalog_product_material_binding_v2 binding
                JOIN catalog_material_v2 material ON material.material_id = binding.material_id
                WHERE binding.config_version_id = :versionId
                  AND binding.status = 'ACTIVE'
                  AND material.status = 'ACTIVE'
                ORDER BY binding.product_id, binding.sort_order, binding.binding_id
                """, versionId));
        response.put("material_colors", rows("""
                SELECT color.material_color_id, color.material_id, color.semantic_type,
                       color.color_code, color.display_name, color.sort_order
                FROM catalog_material_color_v2 color
                JOIN catalog_material_v2 material ON material.material_id = color.material_id
                WHERE material.config_version_id = :versionId
                  AND material.status = 'ACTIVE'
                  AND color.status = 'ACTIVE'
                ORDER BY color.material_id, color.semantic_type, color.sort_order
                """, versionId));
        response.put("accessories", rows("""
                SELECT binding.product_id, binding.variant_id, accessory.accessory_id,
                       accessory.accessory_code, accessory.display_name,
                       accessory.quantity_supported, binding.selection_group_code,
                       binding.required_flag, binding.default_flag,
                       binding.min_quantity, binding.max_quantity,
                       binding.price_increment_cents, binding.sort_order
                FROM catalog_product_accessory_binding_v2 binding
                JOIN catalog_accessory_v2 accessory
                  ON accessory.accessory_id = binding.accessory_id
                WHERE binding.config_version_id = :versionId
                  AND binding.status = 'ACTIVE'
                  AND accessory.status = 'ACTIVE'
                ORDER BY binding.product_id, binding.sort_order, binding.binding_id
                """, versionId));
        response.put("rules", rows("""
                SELECT rule_id, product_id, variant_id, rule_type, rule_code,
                       rule_schema_json, sort_order
                FROM catalog_rule_v2
                WHERE config_version_id = :versionId
                  AND status = 'ACTIVE'
                ORDER BY product_id, rule_type, sort_order, rule_id
                """, versionId));
        return response;
    }

    private void requireManage(BootstrapIdentity identity) {
        if (identity.role() == UserRole.ADMIN || identity.hasPermission("catalog:manage")) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "catalog:manage permission is required");
    }

    private void requirePublish(BootstrapIdentity identity) {
        if (identity.role() == UserRole.ADMIN || identity.hasPermission("catalog:publish")) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "catalog:publish permission is required");
    }

    private void requireDraftVersion(long versionId) {
        if (!"DRAFT".equals(loadVersion(versionId).publicationStatus())) {
            throw conflict("published catalog versions are immutable", null);
        }
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
                            rs.getObject("effective_at", LocalDateTime.class),
                            rs.getInt("lock_version")))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "catalog version not found", ex);
        }
    }

    private MaterialResponse loadMaterial(long materialId) {
        try {
            return jdbcClient.sql("""
                            SELECT material_id, config_version_id, material_code, display_name,
                                   material_family, brand_name, specification,
                                   sort_order, status, lock_version
                            FROM catalog_material_v2
                            WHERE material_id = :materialId
                            """)
                    .param("materialId", materialId)
                    .query((rs, rowNum) -> mapMaterial(rs))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "material not found", ex);
        }
    }

    private static MaterialResponse mapMaterial(ResultSet rs) throws SQLException {
        return new MaterialResponse(
                rs.getLong("material_id"),
                rs.getLong("config_version_id"),
                rs.getString("material_code"),
                rs.getString("display_name"),
                rs.getString("material_family"),
                rs.getString("brand_name"),
                rs.getString("specification"),
                rs.getInt("sort_order"),
                rs.getString("status"),
                rs.getInt("lock_version"));
    }

    private void requireCategoryInVersion(long categoryId, long versionId) {
        long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM catalog_category_v2
                        WHERE category_id = :categoryId
                          AND config_version_id = :versionId
                        """)
                .param("categoryId", categoryId)
                .param("versionId", versionId)
                .query(Long.class)
                .single();
        if (count == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "category is not in this draft");
        }
    }

    private void requireKnownWorkflowProductType(String workflowProductType) {
        if (workflowProductType == null) {
            return;
        }
        long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM workflow_chain
                        WHERE product_type = :productType
                          AND status = 1
                        """)
                .param("productType", workflowProductType.toUpperCase(Locale.ROOT))
                .query(Long.class)
                .single();
        if (count == 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "workflow_product_type must reference an active workflow chain");
        }
    }

    private void requireProductAndMaterialInVersion(
            long productId, Long variantId, long materialId, long versionId) {
        long productCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM catalog_product_v2
                        WHERE product_id = :productId
                          AND config_version_id = :versionId
                        """)
                .param("productId", productId)
                .param("versionId", versionId)
                .query(Long.class)
                .single();
        long materialCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM catalog_material_v2
                        WHERE material_id = :materialId
                          AND config_version_id = :versionId
                        """)
                .param("materialId", materialId)
                .param("versionId", versionId)
                .query(Long.class)
                .single();
        long variantCount = variantId == null
                ? 1
                : jdbcClient.sql("""
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
        if (productCount == 0 || materialCount == 0 || variantCount == 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "product, variant and material must belong to the same draft version");
        }
    }

    private long count(String tableName, long versionId) {
        return jdbcClient.sql(
                        "SELECT COUNT(*) FROM " + tableName + " WHERE config_version_id = :versionId")
                .param("versionId", versionId)
                .query(Long.class)
                .single();
    }

    private void validateActiveRuleSchemas(long versionId) {
        jdbcClient.sql("""
                        SELECT rule_code, rule_type, rule_schema_json
                        FROM catalog_rule_v2
                        WHERE config_version_id = :versionId
                          AND status = 'ACTIVE'
                        ORDER BY rule_id
                        """)
                .param("versionId", versionId)
                .query((rs, rowNum) -> {
                    String ruleCode = rs.getString("rule_code");
                    String ruleType = rs.getString("rule_type");
                    JsonNode schema;
                    try {
                        schema = objectMapper.readTree(rs.getString("rule_schema_json"));
                    } catch (JsonProcessingException ex) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "catalog rule JSON is invalid: " + ruleCode,
                                ex);
                    }
                    try {
                        ruleSchemaValidator.validate(ruleType, schema);
                    } catch (ResponseStatusException ex) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "catalog rule is invalid: " + ruleCode + " - " + ex.getReason(),
                                ex);
                    }
                    return ruleCode;
                })
                .list();
    }

    private long lastId(String table, String idColumn, String versionColumn, long versionId) {
        return jdbcClient.sql("""
                        SELECT %s
                        FROM %s
                        WHERE %s = :versionId
                        ORDER BY %s DESC
                        LIMIT 1
                        """.formatted(idColumn, table, versionColumn, idColumn))
                .param("versionId", versionId)
                .query(Long.class)
                .single();
    }

    private List<Map<String, Object>> rows(String sql, long versionId) {
        return jdbcClient.sql(sql)
                .param("versionId", versionId)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    var metadata = rs.getMetaData();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        row.put(metadata.getColumnLabel(index), rs.getObject(index));
                    }
                    return row;
                })
                .list();
    }

    private Map<String, Object> categorySnapshot(long id) {
        return snapshot(
                "SELECT category_id, config_version_id, category_code, display_name, sort_order, status, lock_version "
                        + "FROM catalog_category_v2 WHERE category_id = :id",
                id);
    }

    private Map<String, Object> productSnapshot(long id) {
        return snapshot(
                "SELECT product_id, config_version_id, category_id, product_code, display_name, "
                        + "workflow_product_type, tooth_rule_code, pricing_status, base_price_cents, "
                        + "currency, sort_order, status, lock_version "
                        + "FROM catalog_product_v2 WHERE product_id = :id",
                id);
    }

    private Map<String, Object> bindingSnapshot(long id) {
        return snapshot(
                "SELECT binding_id, config_version_id, product_id, variant_id, material_id, "
                        + "selection_group_code, "
                        + "required_flag, selection_mode, default_flag, min_quantity, max_quantity, "
                        + "price_increment_cents, sort_order, status, lock_version "
                        + "FROM catalog_product_material_binding_v2 WHERE binding_id = :id",
                id);
    }

    private Map<String, Object> snapshot(String sql, long id) {
        return jdbcClient.sql(sql)
                .param("id", id)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    var metadata = rs.getMetaData();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        row.put(metadata.getColumnLabel(index), rs.getObject(index));
                    }
                    return row;
                })
                .single();
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
                .param("beforeValue", writeNullableJson(before))
                .param("afterValue", writeNullableJson(after))
                .param("operatorUserId", operatorUserId)
                .param("reason", reason)
                .update();
    }

    private String writeNullableJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize catalog audit value", ex);
        }
    }

    private String normalizeCode(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ResponseStatusException conflict(String message, Exception cause) {
        return cause == null
                ? new ResponseStatusException(HttpStatus.CONFLICT, message)
                : new ResponseStatusException(HttpStatus.CONFLICT, message, cause);
    }
}
