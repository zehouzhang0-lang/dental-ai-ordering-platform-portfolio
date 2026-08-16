package com.yuri.aiorder.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 从**当前生效的**目录版本读取产品、变体与材料/配件绑定。
 *
 * <p>原先只有 {@code CaseGroupDraftService} 一处需要，TASK-034 F 批次的「试戴完成后在同一订单上
 * 继续选择成品与材料」也要读同一套数据，因此抽出来共用——两处各写一份对「生效版本」的判定，
 * 迟早会在目录换版时给出不一致的结果。
 */
@Component
public class ActiveCatalogProductReader {

    private final JdbcClient jdbcClient;

    public ActiveCatalogProductReader(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public ActiveProduct loadActiveProduct(long productId, Long variantId) {
        try {
            ActiveProduct product = jdbcClient.sql("""
                            SELECT product.product_id, product.config_version_id,
                                   product.product_code, product.display_name,
                                   product.workflow_product_type, product.tooth_rule_code,
                                   product.pricing_status, product.base_price_cents,
                                   product.currency, category.category_code,
                                   category.display_name AS category_name
                            FROM catalog_product_v2 product
                            JOIN catalog_category_v2 category
                              ON category.category_id = product.category_id
                            JOIN catalog_config_version version
                              ON version.config_version_id = product.config_version_id
                            WHERE product.product_id = :productId
                              AND product.status = 'ACTIVE'
                              AND category.status = 'ACTIVE'
                              AND version.publication_status = 'ACTIVE'
                              AND version.effective_at <= CURRENT_TIMESTAMP(3)
                            """)
                    .param("productId", productId)
                    .query((rs, rowNum) -> new ActiveProduct(
                            rs.getLong("product_id"),
                            rs.getLong("config_version_id"),
                            rs.getString("product_code"),
                            rs.getString("display_name"),
                            rs.getString("workflow_product_type"),
                            rs.getString("tooth_rule_code"),
                            rs.getString("pricing_status"),
                            rs.getObject("base_price_cents", Long.class),
                            rs.getString("currency"),
                            rs.getString("category_code"),
                            rs.getString("category_name"),
                            null,
                            null,
                            null))
                    .single();
            if (variantId == null) {
                return product;
            }
            VariantRow variant = jdbcClient.sql("""
                            SELECT variant_id, variant_code, display_name
                            FROM catalog_product_variant_v2
                            WHERE variant_id = :variantId
                              AND product_id = :productId
                              AND config_version_id = :versionId
                              AND status = 'ACTIVE'
                            """)
                    .param("variantId", variantId)
                    .param("productId", productId)
                    .param("versionId", product.versionId())
                    .query((rs, rowNum) -> new VariantRow(
                            rs.getLong("variant_id"),
                            rs.getString("variant_code"),
                            rs.getString("display_name")))
                    .single();
            return new ActiveProduct(
                    product.productId(),
                    product.versionId(),
                    product.productCode(),
                    product.productName(),
                    product.workflowProductType(),
                    product.toothRuleCode(),
                    product.pricingStatus(),
                    product.basePriceCents(),
                    product.currency(),
                    product.categoryCode(),
                    product.categoryName(),
                    variant.variantId(),
                    variant.variantCode(),
                    variant.variantName());
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "selected product or variant is not available in the active catalog",
                    ex);
        }
    }

    public List<BindingRow> loadBindings(ActiveProduct product, String bindingType) {
        if ("MATERIAL".equals(bindingType)) {
            return jdbcClient.sql("""
                            SELECT binding.material_id AS selectable_id,
                                   binding.selection_group_code,
                                   binding.required_flag, binding.selection_mode,
                                   binding.min_quantity, binding.max_quantity,
                                   binding.price_increment_cents,
                                   material.material_code AS item_code,
                                   material.display_name AS item_name
                            FROM catalog_product_material_binding_v2 binding
                            JOIN catalog_material_v2 material
                              ON material.material_id = binding.material_id
                            WHERE binding.config_version_id = :versionId
                              AND binding.product_id = :productId
                              AND (binding.variant_id IS NULL OR binding.variant_id <=> :variantId)
                              AND binding.status = 'ACTIVE'
                              AND material.status = 'ACTIVE'
                            ORDER BY binding.sort_order, binding.binding_id
                            """)
                    .param("versionId", product.versionId())
                    .param("productId", product.productId())
                    .param("variantId", product.variantId())
                    .query((rs, rowNum) -> mapBinding(rs, "MATERIAL"))
                    .list();
        }
        return jdbcClient.sql("""
                        SELECT binding.accessory_id AS selectable_id,
                               binding.selection_group_code,
                               binding.required_flag, 'MULTIPLE' AS selection_mode,
                               binding.min_quantity, binding.max_quantity,
                               binding.price_increment_cents,
                               accessory.accessory_code AS item_code,
                               accessory.display_name AS item_name
                        FROM catalog_product_accessory_binding_v2 binding
                        JOIN catalog_accessory_v2 accessory
                          ON accessory.accessory_id = binding.accessory_id
                        WHERE binding.config_version_id = :versionId
                          AND binding.product_id = :productId
                          AND (binding.variant_id IS NULL OR binding.variant_id <=> :variantId)
                          AND binding.status = 'ACTIVE'
                          AND accessory.status = 'ACTIVE'
                        ORDER BY binding.sort_order, binding.binding_id
                        """)
                .param("versionId", product.versionId())
                .param("productId", product.productId())
                .param("variantId", product.variantId())
                .query((rs, rowNum) -> mapBinding(rs, "ACCESSORY"))
                .list();
    }

    private static BindingRow mapBinding(ResultSet rs, String type) throws SQLException {
        return new BindingRow(
                type,
                rs.getLong("selectable_id"),
                rs.getString("selection_group_code"),
                rs.getBoolean("required_flag"),
                rs.getString("selection_mode"),
                rs.getObject("min_quantity", Integer.class),
                rs.getObject("max_quantity", Integer.class),
                rs.getObject("price_increment_cents", Long.class),
                rs.getString("item_code"),
                rs.getString("item_name"));
    }

    public record ActiveProduct(
            long productId,
            long versionId,
            String productCode,
            String productName,
            String workflowProductType,
            String toothRuleCode,
            String pricingStatus,
            Long basePriceCents,
            String currency,
            String categoryCode,
            String categoryName,
            Long variantId,
            String variantCode,
            String variantName) {
    }

    public record VariantRow(long variantId, String variantCode, String variantName) {
    }

    public record BindingRow(
            String bindingType,
            long selectableId,
            String selectionGroupCode,
            boolean required,
            String selectionMode,
            Integer minQuantity,
            Integer maxQuantity,
            Long priceIncrementCents,
            String itemCode,
            String itemName) {
    }
}
