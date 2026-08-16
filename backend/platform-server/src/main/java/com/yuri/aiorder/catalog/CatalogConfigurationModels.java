package com.yuri.aiorder.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public final class CatalogConfigurationModels {

    private CatalogConfigurationModels() {
    }

    public record CreateCatalogVersionRequest(
            @JsonProperty("version_name") @NotBlank @Size(max = 128) String versionName,
            @JsonProperty("based_on_version_id") Long basedOnVersionId) {
    }

    public record CreateCategoryRequest(
            @JsonProperty("category_code")
                    @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,95}")
                    String categoryCode,
            @JsonProperty("display_name") @NotBlank @Size(max = 128) String displayName,
            @JsonProperty("sort_order") Integer sortOrder) {
    }

    public record CreateProductRequest(
            @JsonProperty("category_id") @NotNull Long categoryId,
            @JsonProperty("product_code")
                    @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,95}")
                    String productCode,
            @JsonProperty("display_name") @NotBlank @Size(max = 128) String displayName,
            @JsonProperty("workflow_product_type") @Size(max = 64) String workflowProductType,
            @JsonProperty("tooth_rule_code") @Size(max = 96) String toothRuleCode,
            @JsonProperty("sort_order") Integer sortOrder) {
    }

    public record UpdateNamedCatalogEntityRequest(
            @JsonProperty("display_name") @NotBlank @Size(max = 128) String displayName,
            @JsonProperty("sort_order") Integer sortOrder,
            @JsonProperty("status") @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            @JsonProperty("lock_version") @NotNull @Min(0) Integer lockVersion) {
    }

    public record UpdateProductRequest(
            @JsonProperty("display_name") @NotBlank @Size(max = 128) String displayName,
            @JsonProperty("workflow_product_type") @Size(max = 64) String workflowProductType,
            @JsonProperty("tooth_rule_code") @Size(max = 96) String toothRuleCode,
            @JsonProperty("pricing_status")
                    @NotBlank @Pattern(regexp = "PENDING_QUOTE|PRICED")
                    String pricingStatus,
            @JsonProperty("base_price_cents") @Min(0) Long basePriceCents,
            @JsonProperty("currency")
                    @NotBlank @Pattern(regexp = "[A-Z]{3,16}")
                    String currency,
            @JsonProperty("sort_order") Integer sortOrder,
            @JsonProperty("status") @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            @JsonProperty("lock_version") @NotNull @Min(0) Integer lockVersion) {
    }

    public record CreateVariantRequest(
            @JsonProperty("product_id") @NotNull Long productId,
            @JsonProperty("variant_code")
                    @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,95}")
                    String variantCode,
            @JsonProperty("display_name") @NotBlank @Size(max = 128) String displayName,
            @JsonProperty("attributes") JsonNode attributes,
            @JsonProperty("sort_order") Integer sortOrder) {
    }

    public record CreateAccessoryRequest(
            @JsonProperty("accessory_code")
                    @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,95}")
                    String accessoryCode,
            @JsonProperty("display_name") @NotBlank @Size(max = 128) String displayName,
            @JsonProperty("quantity_supported") Boolean quantitySupported,
            @JsonProperty("sort_order") Integer sortOrder) {
    }

    public record CreateMaterialColorRequest(
            @JsonProperty("material_id") @NotNull Long materialId,
            @JsonProperty("semantic_type")
                    @NotBlank
                    @Pattern(regexp = "TOOTH_SHADE|GINGIVAL_SHADE|DENTURE_BASE_SHADE|ALIGNER_COLOR")
                    String semanticType,
            @JsonProperty("color_code") @NotBlank @Size(max = 64) String colorCode,
            @JsonProperty("display_name") @NotBlank @Size(max = 128) String displayName,
            @JsonProperty("sort_order") Integer sortOrder) {
    }

    public record CreateAccessoryBindingRequest(
            @JsonProperty("product_id") @NotNull Long productId,
            @JsonProperty("variant_id") Long variantId,
            @JsonProperty("accessory_id") @NotNull Long accessoryId,
            @JsonProperty("selection_group_code")
                    @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,95}")
                    String selectionGroupCode,
            @JsonProperty("required") Boolean required,
            @JsonProperty("default") Boolean defaultValue,
            @JsonProperty("min_quantity") @Min(0) Integer minQuantity,
            @JsonProperty("max_quantity") @Min(0) Integer maxQuantity,
            @JsonProperty("price_increment_cents") @Min(0) Long priceIncrementCents,
            @JsonProperty("sort_order") Integer sortOrder) {
    }

    public record CreateAliasRequest(
            @JsonProperty("canonical_type")
                    @NotBlank @Pattern(regexp = "PRODUCT|PRODUCT_VARIANT|MATERIAL|ACCESSORY")
                    String canonicalType,
            @JsonProperty("canonical_id") @NotNull Long canonicalId,
            @JsonProperty("alias_text") @NotBlank @Size(max = 255) String aliasText) {
    }

    public record UpdateAliasRequest(
            @JsonProperty("alias_text") @NotBlank @Size(max = 255) String aliasText,
            @JsonProperty("status") @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            @JsonProperty("lock_version") @NotNull @Min(0) Integer lockVersion) {
    }

    public record CreateCatalogRuleRequest(
            @JsonProperty("product_id") Long productId,
            @JsonProperty("variant_id") Long variantId,
            @JsonProperty("rule_type")
                    @NotBlank @Pattern(regexp = "FORM_SCHEMA|TOOTH|UPLOAD|PRICE|LEAD_TIME|WORKFLOW")
                    String ruleType,
            @JsonProperty("rule_code")
                    @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,95}")
                    String ruleCode,
            @JsonProperty("rule_schema") @NotNull JsonNode ruleSchema,
            @JsonProperty("sort_order") Integer sortOrder) {
    }

    public record UpdateCatalogRuleRequest(
            @JsonProperty("rule_schema") @NotNull JsonNode ruleSchema,
            @JsonProperty("sort_order") Integer sortOrder,
            @JsonProperty("status") @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            @JsonProperty("lock_version") @NotNull @Min(0) Integer lockVersion) {
    }

    public record CreateMaterialRequest(
            @JsonProperty("material_code")
                    @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,95}")
                    String materialCode,
            @JsonProperty("display_name") @NotBlank @Size(max = 128) String displayName,
            @JsonProperty("material_family") @Size(max = 64) String materialFamily,
            @JsonProperty("brand_name") @Size(max = 128) String brandName,
            @JsonProperty("specification") @Size(max = 255) String specification,
            @JsonProperty("sort_order") Integer sortOrder) {
    }

    public record UpdateMaterialRequest(
            @JsonProperty("display_name") @NotBlank @Size(max = 128) String displayName,
            @JsonProperty("material_family") @Size(max = 64) String materialFamily,
            @JsonProperty("brand_name") @Size(max = 128) String brandName,
            @JsonProperty("specification") @Size(max = 255) String specification,
            @JsonProperty("sort_order") Integer sortOrder,
            @JsonProperty("status") @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            @JsonProperty("lock_version") @NotNull @Min(0) Integer lockVersion) {
    }

    public record CreateMaterialBindingRequest(
            @JsonProperty("product_id") @NotNull Long productId,
            @JsonProperty("variant_id") Long variantId,
            @JsonProperty("material_id") @NotNull Long materialId,
            @JsonProperty("selection_group_code")
                    @Pattern(regexp = "[A-Z][A-Z0-9_]{1,95}")
                    String selectionGroupCode,
            @JsonProperty("required") Boolean required,
            @JsonProperty("selection_mode")
                    @NotBlank @Pattern(regexp = "SINGLE|MULTIPLE")
                    String selectionMode,
            @JsonProperty("default") Boolean defaultValue,
            @JsonProperty("min_quantity") @Min(0) Integer minQuantity,
            @JsonProperty("max_quantity") @Min(0) Integer maxQuantity,
            @JsonProperty("price_increment_cents") @Min(0) Long priceIncrementCents,
            @JsonProperty("sort_order") Integer sortOrder) {
    }

    public record UpdateMaterialBindingRequest(
            @JsonProperty("selection_group_code")
                    @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,95}")
                    String selectionGroupCode,
            @JsonProperty("required") Boolean required,
            @JsonProperty("selection_mode")
                    @NotBlank @Pattern(regexp = "SINGLE|MULTIPLE")
                    String selectionMode,
            @JsonProperty("default") Boolean defaultValue,
            @JsonProperty("min_quantity") @Min(0) Integer minQuantity,
            @JsonProperty("max_quantity") @Min(0) Integer maxQuantity,
            @JsonProperty("price_increment_cents") @Min(0) Long priceIncrementCents,
            @JsonProperty("sort_order") Integer sortOrder,
            @JsonProperty("status") @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            @JsonProperty("lock_version") @NotNull @Min(0) Integer lockVersion) {
    }

    public record UpdateAccessoryBindingRequest(
            @JsonProperty("selection_group_code")
                    @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,95}")
                    String selectionGroupCode,
            @JsonProperty("required") Boolean required,
            @JsonProperty("default") Boolean defaultValue,
            @JsonProperty("min_quantity") @Min(0) Integer minQuantity,
            @JsonProperty("max_quantity") @Min(0) Integer maxQuantity,
            @JsonProperty("price_increment_cents") @Min(0) Long priceIncrementCents,
            @JsonProperty("sort_order") Integer sortOrder,
            @JsonProperty("status") @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            @JsonProperty("lock_version") @NotNull @Min(0) Integer lockVersion) {
    }

    public record PublishCatalogRequest(
            @JsonProperty("effective_at") LocalDateTime effectiveAt,
            @JsonProperty("reason") @NotBlank @Size(max = 512) String reason,
            @JsonProperty("lock_version") @NotNull @Min(0) Integer lockVersion) {
    }

    public record CatalogVersionResponse(
            @JsonProperty("config_version_id") long configVersionId,
            @JsonProperty("version_no") int versionNo,
            @JsonProperty("version_name") String versionName,
            @JsonProperty("publication_status") String publicationStatus,
            @JsonProperty("effective_at") LocalDateTime effectiveAt,
            @JsonProperty("lock_version") int lockVersion) {
    }

    public record MaterialResponse(
            @JsonProperty("material_id") long materialId,
            @JsonProperty("config_version_id") long configVersionId,
            @JsonProperty("material_code") String materialCode,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("material_family") String materialFamily,
            @JsonProperty("brand_name") String brandName,
            @JsonProperty("specification") String specification,
            @JsonProperty("sort_order") int sortOrder,
            @JsonProperty("status") String status,
            @JsonProperty("lock_version") int lockVersion) {
    }
}
