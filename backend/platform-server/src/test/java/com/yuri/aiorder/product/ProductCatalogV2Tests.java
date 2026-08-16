package com.yuri.aiorder.product;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ProductCatalogV2Tests {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    private long versionId;
    private String suffix;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        int versionNo = jdbcClient.sql("SELECT COALESCE(MAX(version_no), 0) + 1 FROM catalog_config_version")
                .query(Integer.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO catalog_config_version
                            (version_no, version_name, publication_status)
                        VALUES (:versionNo, :name, 'DRAFT')
                        """)
                .param("versionNo", versionNo)
                .param("name", "目录测试-" + suffix)
                .update();
        versionId = jdbcClient.sql("""
                        SELECT config_version_id
                        FROM catalog_config_version
                        WHERE version_no = :versionNo
                        """)
                .param("versionNo", versionNo)
                .query(Long.class)
                .single();
    }

    @Test
    void migratedCatalogPublishesConfirmedClearAlignerAndKeepsPlaceholderInactive() {
        long confirmedProducts = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM catalog_product_v2 product
                        JOIN catalog_config_version version
                          ON version.config_version_id = product.config_version_id
                        WHERE version.version_name = '隐形正畸正式产品目录 2026-08-01'
                          AND product.product_code = 'CLEAR_ALIGNER_BRACELESS'
                          AND product.display_name = '无托槽隐形矫治器'
                          AND product.status = 'ACTIVE'
                          AND product.pricing_status = 'PENDING_QUOTE'
                        """)
                .query(Long.class)
                .single();
        long enabledRules = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM catalog_rule_v2 rule
                        JOIN catalog_product_v2 product ON product.product_id = rule.product_id
                        JOIN catalog_config_version version
                          ON version.config_version_id = product.config_version_id
                        WHERE version.version_name = '隐形正畸正式产品目录 2026-08-01'
                          AND product.product_code = 'CLEAR_ALIGNER_BRACELESS'
                          AND rule.rule_type = 'WORKFLOW'
                          AND JSON_SEARCH(
                              rule.rule_schema_json,
                              'one',
                              'CLEAR_ALIGNER_BRACELESS',
                              NULL,
                              '$.aligner_types[*].code'
                          ) IS NOT NULL
                        """)
                .query(Long.class)
                .single();
        long activePlaceholders = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM catalog_product_v2 product
                        JOIN catalog_config_version version
                          ON version.config_version_id = product.config_version_id
                        WHERE version.version_name = '隐形正畸正式产品目录 2026-08-01'
                          AND product.product_code = 'CLEAR_ALIGNER_TYPE_A'
                          AND product.status = 'ACTIVE'
                        """)
                .query(Long.class)
                .single();

        org.assertj.core.api.Assertions.assertThat(confirmedProducts).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(enabledRules).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(activePlaceholders).isZero();
    }

    @Test
    void adminCanMaintainBindPreviewPublishAndPublishedVersionIsImmutable() throws Exception {
        createCategory();
        long categoryId = categoryId();
        createProduct(categoryId, "PRODUCT_A_" + suffix, "测试产品 A");
        createProduct(categoryId, "PRODUCT_B_" + suffix, "测试产品 B");
        long firstProductId = productId("PRODUCT_A_" + suffix);
        long secondProductId = productId("PRODUCT_B_" + suffix);
        long materialId = createMaterial("MATERIAL_" + suffix, "Lucitone 测试材料");

        bindMaterial(firstProductId, materialId, "SINGLE", 1, 1)
                .andExpect(status().isOk());
        bindMaterial(secondProductId, materialId, "MULTIPLE", 0, 3)
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/catalog/versions/{versionId}/preview", versionId)
                        .header("X-Bootstrap-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.products.length()").value(2))
                .andExpect(jsonPath("$.data.materials.length()").value(1))
                .andExpect(jsonPath("$.data.material_bindings.length()").value(2));

        mockMvc.perform(delete("/admin/catalog/materials/{materialId}", materialId)
                        .header("X-Bootstrap-Role", "ADMIN"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/admin/catalog/versions/{versionId}/publish", versionId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"测试发布","lock_version":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publication_status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.lock_version").value(1));

        mockMvc.perform(get("/catalog/configuration/active")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", 98701)
                        .header("X-Bootstrap-Clinic-Id", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publication_status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.products.length()").value(2))
                .andExpect(jsonPath("$.data.materials.length()").value(2));

        mockMvc.perform(put("/admin/catalog/materials/{materialId}", materialId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "display_name":"发布后不允许修改",
                                  "status":"INACTIVE",
                                  "lock_version":0
                                }
                                """))
                .andExpect(status().isConflict());

        long auditCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM catalog_change_audit
                        WHERE config_version_id = :versionId
                          AND action_type IN ('CREATE', 'BIND', 'PUBLISH')
                        """)
                .param("versionId", versionId)
                .query(Long.class)
                .single();
        org.assertj.core.api.Assertions.assertThat(auditCount).isEqualTo(7L);
    }

    @Test
    void draftMaterialSupportsOptimisticUpdateAndOnlyUnreferencedDraftCanBeDeleted() throws Exception {
        long materialId = createMaterial("DELETE_" + suffix, "可删除草稿材料");

        mockMvc.perform(put("/admin/catalog/materials/{materialId}", materialId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "display_name":"更新后的草稿材料",
                                  "material_family":"ACRYLIC",
                                  "brand_name":"测试品牌",
                                  "specification":"测试规格",
                                  "sort_order":8,
                                  "status":"INACTIVE",
                                  "lock_version":0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"))
                .andExpect(jsonPath("$.data.lock_version").value(1));

        mockMvc.perform(put("/admin/catalog/materials/{materialId}", materialId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "display_name":"并发覆盖",
                                  "status":"ACTIVE",
                                  "lock_version":0
                                }
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(delete("/admin/catalog/materials/{materialId}", materialId)
                        .header("X-Bootstrap-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));
    }

    @Test
    void previewExposesProductLockAndDraftProductCanBeUpdatedThenDeleted() throws Exception {
        createCategory();
        long categoryId = categoryId();
        createProduct(categoryId, "EDIT_" + suffix, "待修改产品");
        long productId = productId("EDIT_" + suffix);

        mockMvc.perform(get("/admin/catalog/versions/{versionId}/preview", versionId)
                        .header("X-Bootstrap-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.products[0].config_version_id").value(versionId))
                .andExpect(jsonPath("$.data.products[0].lock_version").value(0));

        mockMvc.perform(put("/admin/catalog/products/{productId}", productId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "display_name":"修改后的产品",
                                  "workflow_product_type":"REGULAR_CROWN",
                                  "tooth_rule_code":null,
                                  "pricing_status":"PENDING_QUOTE",
                                  "base_price_cents":null,
                                  "currency":"CNY",
                                  "sort_order":3,
                                  "status":"ACTIVE",
                                  "lock_version":0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.display_name").value("修改后的产品"))
                .andExpect(jsonPath("$.data.lock_version").value(1));

        mockMvc.perform(delete("/admin/catalog/entities/PRODUCT/{productId}", productId)
                        .header("X-Bootstrap-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));
    }

    @Test
    void draftCategoryCanBeUpdatedAndDeletedOnlyWhenItHasNoProducts() throws Exception {
        createCategory();
        long emptyCategoryId = categoryId();

        mockMvc.perform(get("/admin/catalog/versions/{versionId}/preview", versionId)
                        .header("X-Bootstrap-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories[0].config_version_id").value(versionId))
                .andExpect(jsonPath("$.data.categories[0].lock_version").value(0));

        mockMvc.perform(put("/admin/catalog/entities/CATEGORY/{categoryId}", emptyCategoryId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "display_name":"更新后的分类",
                                  "sort_order":2,
                                  "status":"ACTIVE",
                                  "lock_version":0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.display_name").value("更新后的分类"))
                .andExpect(jsonPath("$.data.lock_version").value(1));

        mockMvc.perform(delete("/admin/catalog/entities/CATEGORY/{categoryId}", emptyCategoryId)
                        .header("X-Bootstrap-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));

        createCategory();
        long usedCategoryId = categoryId();
        createProduct(usedCategoryId, "CATEGORY_USED_" + suffix, "分类引用产品");

        mockMvc.perform(delete("/admin/catalog/entities/CATEGORY/{categoryId}", usedCategoryId)
                        .header("X-Bootstrap-Role", "ADMIN"))
                .andExpect(status().isConflict());
    }

    @Test
    void unauthorizedRolesCannotMaintainCatalogAndBindingRulesAreValidated() throws Exception {
        mockMvc.perform(post("/admin/catalog/versions/{versionId}/materials", versionId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", 98702)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"material_code":"DENIED_%s","display_name":"越权材料"}
                                """.formatted(suffix)))
                .andExpect(status().isForbidden());

        createCategory();
        long categoryId = categoryId();
        mockMvc.perform(post("/admin/catalog/versions/{versionId}/products", versionId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category_id":%d,
                                  "product_code":"INVALID_%s",
                                  "display_name":"错误工序链产品",
                                  "workflow_product_type":"NOT_A_REAL_CHAIN"
                                }
                                """.formatted(categoryId, suffix)))
                .andExpect(status().isBadRequest());
        createProduct(categoryId, "PRODUCT_RULE_" + suffix, "规则测试产品");
        long productId = productId("PRODUCT_RULE_" + suffix);
        long materialId = createMaterial("RULE_" + suffix, "规则测试材料");

        bindMaterial(productId, materialId, "MULTIPLE", 4, 2)
                .andExpect(status().isBadRequest());
        bindMaterial(productId, materialId, "SINGLE", -1, 1)
                .andExpect(status().isBadRequest());
    }

    @Test
    void fullCatalogDraftCanBePreviewedPublishedAndCopiedWithoutLosingRelations() throws Exception {
        createCategory();
        long categoryId = categoryId();
        createProduct(categoryId, "FULL_" + suffix, "完整目录产品");
        long productId = productId("FULL_" + suffix);
        long materialId = createMaterial("FULL_MAT_" + suffix, "完整目录材料");

        mockMvc.perform(post("/admin/catalog/versions/{versionId}/variants", versionId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product_id":%d,
                                  "variant_code":"VARIANT_%s",
                                  "display_name":"A 型",
                                  "attributes":{"enabled":true},
                                  "sort_order":1
                                }
                                """.formatted(productId, suffix)))
                .andExpect(status().isOk());
        long variantId = jdbcClient.sql("""
                        SELECT variant_id FROM catalog_product_variant_v2
                        WHERE config_version_id = :versionId
                        ORDER BY variant_id DESC LIMIT 1
                        """)
                .param("versionId", versionId)
                .query(Long.class)
                .single();

        mockMvc.perform(post("/admin/catalog/versions/{versionId}/material-colors", versionId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "material_id":%d,
                                  "semantic_type":"DENTURE_BASE_SHADE",
                                  "color_code":"PINK_%s",
                                  "display_name":"基托粉",
                                  "sort_order":1
                                }
                                """.formatted(materialId, suffix)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/catalog/versions/{versionId}/accessories", versionId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accessory_code":"ACC_%s",
                                  "display_name":"测试配件",
                                  "quantity_supported":true,
                                  "sort_order":1
                                }
                                """.formatted(suffix)))
                .andExpect(status().isOk());
        long accessoryId = jdbcClient.sql("""
                        SELECT accessory_id FROM catalog_accessory_v2
                        WHERE config_version_id = :versionId
                        ORDER BY accessory_id DESC LIMIT 1
                        """)
                .param("versionId", versionId)
                .query(Long.class)
                .single();

        bindMaterial(productId, materialId, "SINGLE", 1, 1)
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/catalog/versions/{versionId}/accessory-bindings", versionId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product_id":%d,
                                  "variant_id":%d,
                                  "accessory_id":%d,
                                  "selection_group_code":"ORTHO_ACCESSORIES",
                                  "required":false,
                                  "default":false,
                                  "min_quantity":0,
                                  "max_quantity":4,
                                  "sort_order":1
                                }
                                """.formatted(productId, variantId, accessoryId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/catalog/versions/{versionId}/aliases", versionId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "canonical_type":"PRODUCT",
                                  "canonical_id":%d,
                                  "alias_text":"Complete Denture %s"
                                }
                                """.formatted(productId, suffix)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/catalog/versions/{versionId}/rules", versionId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product_id":%d,
                                  "rule_type":"FORM_SCHEMA",
                                  "rule_code":"FORM_%s",
                                  "rule_schema":{"fields":[{"key":"quantity","type":"number"}]},
                                  "sort_order":1
                                }
                                """.formatted(productId, suffix)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/catalog/versions/{versionId}/preview", versionId)
                        .header("X-Bootstrap-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.variants.length()").value(1))
                .andExpect(jsonPath("$.data.material_colors.length()").value(1))
                .andExpect(jsonPath("$.data.accessories.length()").value(1))
                .andExpect(jsonPath("$.data.accessory_bindings.length()").value(1))
                .andExpect(jsonPath("$.data.aliases.length()").value(1))
                .andExpect(jsonPath("$.data.rules.length()").value(1));

        long materialBindingId = jdbcClient.sql("""
                        SELECT binding_id FROM catalog_product_material_binding_v2
                        WHERE config_version_id = :versionId
                        """)
                .param("versionId", versionId)
                .query(Long.class)
                .single();
        mockMvc.perform(put("/admin/catalog/material-bindings/{bindingId}", materialBindingId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "selection_group_code":"PRIMARY_MATERIAL",
                                  "required":true,
                                  "selection_mode":"SINGLE",
                                  "default":true,
                                  "min_quantity":1,
                                  "max_quantity":1,
                                  "price_increment_cents":2500,
                                  "sort_order":2,
                                  "status":"ACTIVE",
                                  "lock_version":0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price_increment_cents").value(2500));

        long accessoryBindingId = jdbcClient.sql("""
                        SELECT binding_id FROM catalog_product_accessory_binding_v2
                        WHERE config_version_id = :versionId
                        """)
                .param("versionId", versionId)
                .query(Long.class)
                .single();
        mockMvc.perform(put("/admin/catalog/accessory-bindings/{bindingId}", accessoryBindingId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "selection_group_code":"ORTHO_ACCESSORIES",
                                  "required":false,
                                  "default":false,
                                  "min_quantity":0,
                                  "max_quantity":6,
                                  "price_increment_cents":500,
                                  "sort_order":2,
                                  "status":"ACTIVE",
                                  "lock_version":0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.max_quantity").value(6));

        long aliasId = jdbcClient.sql("""
                        SELECT alias_id FROM catalog_alias_v2
                        WHERE config_version_id = :versionId
                        """)
                .param("versionId", versionId)
                .query(Long.class)
                .single();
        mockMvc.perform(put("/admin/catalog/aliases/{aliasId}", aliasId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "alias_text":"Full Denture %s",
                                  "status":"ACTIVE",
                                  "lock_version":0
                                }
                                """.formatted(suffix)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alias_text").value("Full Denture " + suffix));

        mockMvc.perform(post("/admin/catalog/versions/{versionId}/publish", versionId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"完整目录发布","lock_version":0}
                                """))
                .andExpect(status().isOk());

        String copyName = "完整目录复制-" + suffix;
        mockMvc.perform(post("/admin/catalog/versions")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version_name":"%s",
                                  "based_on_version_id":%d
                                }
                                """.formatted(copyName, versionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publication_status").value("DRAFT"));
        long copiedVersionId = jdbcClient.sql("""
                        SELECT config_version_id
                        FROM catalog_config_version
                        WHERE version_name = :name
                        """)
                .param("name", copyName)
                .query(Long.class)
                .single();

        mockMvc.perform(get("/admin/catalog/versions/{versionId}/preview", copiedVersionId)
                        .header("X-Bootstrap-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.products.length()").value(1))
                .andExpect(jsonPath("$.data.variants.length()").value(1))
                .andExpect(jsonPath("$.data.material_colors.length()").value(1))
                .andExpect(jsonPath("$.data.accessory_bindings.length()").value(1))
                .andExpect(jsonPath("$.data.aliases.length()").value(1))
                .andExpect(jsonPath("$.data.rules.length()").value(1));
    }

    @Test
    void formSchemaRejectsUnsupportedTypesDuplicateKeysInvalidUpdatesAndInvalidPublishData()
            throws Exception {
        createCategory();
        long categoryId = categoryId();
        createProduct(categoryId, "SCHEMA_" + suffix, "Schema 校验产品");
        long productId = productId("SCHEMA_" + suffix);

        mockMvc.perform(post("/admin/catalog/versions/{versionId}/rules", versionId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product_id":%d,
                                  "rule_type":"FORM_SCHEMA",
                                  "rule_code":"BAD_TYPE_%s",
                                  "rule_schema":{"fields":[{"key":"quantity","type":"unknown"}]}
                                }
                                """.formatted(productId, suffix)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/admin/catalog/versions/{versionId}/rules", versionId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product_id":%d,
                                  "rule_type":"FORM_SCHEMA",
                                  "rule_code":"DUPLICATE_%s",
                                  "rule_schema":{"fields":[
                                    {"key":"quantity","type":"quantity","minimum":1,"maximum":4},
                                    {"key":"quantity","type":"number"}
                                  ]}
                                }
                                """.formatted(productId, suffix)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/admin/catalog/versions/{versionId}/rules", versionId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product_id":%d,
                                  "rule_type":"FORM_SCHEMA",
                                  "rule_code":"VALID_%s",
                                  "rule_schema":{"fields":[
                                    {"key":"quantity","type":"quantity","minimum":1,"maximum":4},
                                    {"key":"tags","type":"multi_select","options":["fixed","implant"]},
                                    {"key":"metadata","type":"object"},
                                    {"key":"urgent","type":"boolean"},
                                    {"key":"reason","type":"textarea","visible_when":{"field":"urgent","equals":true}}
                                  ]}
                                }
                                """.formatted(productId, suffix)))
                .andExpect(status().isOk());

        long ruleId = jdbcClient.sql("""
                        SELECT rule_id
                        FROM catalog_rule_v2
                        WHERE config_version_id = :versionId
                          AND rule_code = :ruleCode
                        """)
                .param("versionId", versionId)
                .param("ruleCode", "VALID_" + suffix)
                .query(Long.class)
                .single();
        mockMvc.perform(put("/admin/catalog/rules/{ruleId}", ruleId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "rule_schema":{"fields":[{"key":"quantity","type":"quantity","minimum":5,"maximum":1}]},
                                  "sort_order":0,
                                  "status":"ACTIVE",
                                  "lock_version":0
                                }
                                """))
                .andExpect(status().isBadRequest());

        jdbcClient.sql("""
                        INSERT INTO catalog_rule_v2
                            (config_version_id, product_id, rule_type, rule_code, rule_schema_json)
                        VALUES
                            (:versionId, :productId, 'FORM_SCHEMA', :ruleCode,
                             JSON_OBJECT('fields', JSON_ARRAY(JSON_OBJECT('key', 'unsafe', 'type', 'unsupported'))))
                        """)
                .param("versionId", versionId)
                .param("productId", productId)
                .param("ruleCode", "LEGACY_BAD_" + suffix)
                .update();

        mockMvc.perform(post("/admin/catalog/versions/{versionId}/publish", versionId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"非法 schema 不得发布","lock_version":0}
                                """))
                .andExpect(status().isBadRequest());
        String publicationStatus = jdbcClient.sql("""
                        SELECT publication_status
                        FROM catalog_config_version
                        WHERE config_version_id = :versionId
                        """)
                .param("versionId", versionId)
                .query(String.class)
                .single();
        org.assertj.core.api.Assertions.assertThat(publicationStatus).isEqualTo("DRAFT");
    }

    @Test
    void catalogImportTemplateAndValidationAreStableAndNeverWriteData() throws Exception {
        mockMvc.perform(get("/admin/catalog/import-template")
                        .header("X-Bootstrap-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.template_version").value("CATALOG_V2_1"))
                .andExpect(jsonPath("$.data.writes_data").value(false));

        mockMvc.perform(post("/admin/catalog/import-validation")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "template_version":"CATALOG_V2_1",
                                  "rows":[
                                    {"entity_type":"MATERIAL","code":"LUCITONE_199","display_name":"Lucitone 199"},
                                    {"entity_type":"MATERIAL","code":"LUCITONE_199","display_name":"重复材料"},
                                    {"entity_type":"PRODUCT","code":"bad code","display_name":""}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.row_count").value(3))
                .andExpect(jsonPath("$.data.error_count").value(3))
                .andExpect(jsonPath("$.data.writes_data").value(false));

        mockMvc.perform(get("/admin/catalog/import-template")
                        .header("X-Bootstrap-Role", "DOCTOR"))
                .andExpect(status().isForbidden());
    }

    private void createCategory() throws Exception {
        mockMvc.perform(post("/admin/catalog/versions/{versionId}/categories", versionId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category_code":"CATEGORY_%s",
                                  "display_name":"测试分类",
                                  "sort_order":1
                                }
                                """.formatted(suffix)))
                .andExpect(status().isOk());
    }

    private long categoryId() {
        return jdbcClient.sql("""
                        SELECT category_id
                        FROM catalog_category_v2
                        WHERE config_version_id = :versionId
                        ORDER BY category_id DESC
                        LIMIT 1
                        """)
                .param("versionId", versionId)
                .query(Long.class)
                .single();
    }

    private void createProduct(long categoryId, String code, String name) throws Exception {
        mockMvc.perform(post("/admin/catalog/versions/{versionId}/products", versionId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category_id":%d,
                                  "product_code":"%s",
                                  "display_name":"%s",
                                  "workflow_product_type":"REGULAR_CROWN",
                                  "tooth_rule_code":"TOOTH_FIXED",
                                  "sort_order":1
                                }
                                """.formatted(categoryId, code, name)))
                .andExpect(status().isOk());
    }

    private long productId(String code) {
        return jdbcClient.sql("""
                        SELECT product_id
                        FROM catalog_product_v2
                        WHERE config_version_id = :versionId
                          AND product_code = :code
                        """)
                .param("versionId", versionId)
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private long createMaterial(String code, String name) throws Exception {
        mockMvc.perform(post("/admin/catalog/versions/{versionId}/materials", versionId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "material_code":"%s",
                                  "display_name":"%s",
                                  "material_family":"ACRYLIC",
                                  "brand_name":"测试品牌",
                                  "specification":"测试规格",
                                  "sort_order":1
                                }
                                """.formatted(code, name)))
                .andExpect(status().isOk());
        return jdbcClient.sql("""
                        SELECT material_id
                        FROM catalog_material_v2
                        WHERE config_version_id = :versionId
                          AND material_code = :code
                        """)
                .param("versionId", versionId)
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private org.springframework.test.web.servlet.ResultActions bindMaterial(
            long productId, long materialId, String mode, int min, int max) throws Exception {
        return mockMvc.perform(post("/admin/catalog/versions/{versionId}/material-bindings", versionId)
                .header("X-Bootstrap-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "product_id":%d,
                          "material_id":%d,
                          "required":true,
                          "selection_mode":"%s",
                          "default":false,
                          "min_quantity":%d,
                          "max_quantity":%d,
                          "sort_order":1
                        }
                        """.formatted(productId, materialId, mode, min, max)));
    }
}
