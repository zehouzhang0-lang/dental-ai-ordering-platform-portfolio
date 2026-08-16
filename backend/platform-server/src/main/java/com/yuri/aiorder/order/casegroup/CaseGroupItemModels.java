package com.yuri.aiorder.order.casegroup;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class CaseGroupItemModels {

    private CaseGroupItemModels() {
    }

    public record QuantitySelection(
            @JsonProperty("item_id") @NotNull Long itemId,
            @JsonProperty("quantity") @NotNull @Min(0) Integer quantity) {
    }

    public record CreateGroupItemRequest(
            @JsonProperty("product_id") @NotNull Long productId,
            @JsonProperty("variant_id") Long variantId,
            @JsonProperty("relationship_type")
                    @Pattern(regexp = "PRIMARY|RELATED")
                    String relationshipType,
            @JsonProperty("item_client_key")
                    @NotBlank @Size(max = 128)
                    String itemClientKey,
            @JsonProperty("form_values") @NotNull JsonNode formValues,
            @JsonProperty("material_selections") @Valid List<QuantitySelection> materialSelections,
            @JsonProperty("accessory_selections") @Valid List<QuantitySelection> accessorySelections,
            @JsonProperty("file_ids") List<Long> fileIds,
            @JsonProperty("expected_draft_version") @NotNull @Min(1) Integer expectedDraftVersion) {
    }

    public record UpdateGroupItemRequest(
            @JsonProperty("product_id") @NotNull Long productId,
            @JsonProperty("variant_id") Long variantId,
            @JsonProperty("relationship_type")
                    @Pattern(regexp = "PRIMARY|RELATED")
                    String relationshipType,
            @JsonProperty("form_values") @NotNull JsonNode formValues,
            @JsonProperty("material_selections") @Valid List<QuantitySelection> materialSelections,
            @JsonProperty("accessory_selections") @Valid List<QuantitySelection> accessorySelections,
            @JsonProperty("file_ids") List<Long> fileIds,
            @JsonProperty("expected_draft_version") @NotNull @Min(1) Integer expectedDraftVersion) {
    }

    public record CopyGroupItemRequest(
            @JsonProperty("item_client_key")
                    @NotBlank @Size(max = 128)
                    String itemClientKey,
            @JsonProperty("expected_draft_version") @NotNull @Min(1) Integer expectedDraftVersion) {
    }

    public record DeleteGroupItemRequest(
            @JsonProperty("expected_draft_version") @NotNull @Min(1) Integer expectedDraftVersion) {
    }

    public record SubmitCaseGroupRequest(
            @JsonProperty("idempotency_key")
                    @NotBlank @Size(max = 128)
                    String idempotencyKey,
            @JsonProperty("expected_draft_version") @NotNull @Min(1) Integer expectedDraftVersion) {
    }

    public record BindSharedFilesRequest(
            @JsonProperty("file_ids") @NotNull List<Long> fileIds,
            @JsonProperty("expected_draft_version") @NotNull @Min(1) Integer expectedDraftVersion) {
    }
}
