package com.yuri.aiorder.order.casegroup;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;

public record CaseGroupResponse(
        @JsonProperty("group_id") long groupId,
        @JsonProperty("group_no") String groupNo,
        @JsonProperty("clinic_id") long clinicId,
        @JsonProperty("doctor_user_id") Long doctorUserId,
        @JsonProperty("patient_id") Long patientId,
        @JsonProperty("lifecycle_status") String lifecycleStatus,
        @JsonProperty("external_status") String externalStatus,
        @JsonProperty("draft_version") int draftVersion,
        @JsonProperty("shared_file_ids") List<Long> sharedFileIds,
        @JsonProperty("items") List<CaseGroupItemResponse> items,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt) {

    public record CaseGroupItemResponse(
            @JsonProperty("order_id") long orderId,
            @JsonProperty("order_no") String orderNo,
            @JsonProperty("line_no") int lineNo,
            @JsonProperty("relationship_type") String relationshipType,
            @JsonProperty("item_client_key") String itemClientKey,
            @JsonProperty("product_id") Long productId,
            @JsonProperty("product_code") String productCode,
            @JsonProperty("product_name") String productName,
            @JsonProperty("variant_id") Long variantId,
            @JsonProperty("variant_code") String variantCode,
            @JsonProperty("variant_name") String variantName,
            @JsonProperty("product_type") String productType,
            @JsonProperty("pricing_status") String pricingStatus,
            @JsonProperty("quoted_price_cents") Long quotedPriceCents,
            @JsonProperty("quoted_price_currency") String quotedPriceCurrency,
            @JsonProperty("configuration_status") String configurationStatus,
            @JsonProperty("form_values") JsonNode formValues,
            @JsonProperty("material_selections") JsonNode materialSelections,
            @JsonProperty("accessory_selections") JsonNode accessorySelections,
            @JsonProperty("file_ids") List<Long> fileIds,
            @JsonProperty("external_status") String externalStatus,
            @JsonProperty("created_at") LocalDateTime createdAt) {
    }
}
