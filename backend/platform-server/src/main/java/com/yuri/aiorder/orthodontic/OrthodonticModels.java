package com.yuri.aiorder.orthodontic;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class OrthodonticModels {

    private OrthodonticModels() {
    }

    public record SavePrescriptionRequest(
            @JsonProperty("aligner_type_code")
                    @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,95}")
                    String alignerTypeCode,
            @JsonProperty("combined_order_id") Long combinedOrderId,
            @JsonProperty("total_steps") @Min(1) @Max(999) Integer totalSteps,
            @JsonProperty("basic_information") @NotNull JsonNode basicInformation,
            @JsonProperty("records_and_models") @NotNull JsonNode recordsAndModels,
            @JsonProperty("clinical_diagnosis") @NotNull JsonNode clinicalDiagnosis,
            @JsonProperty("appliance_and_combination") @NotNull JsonNode applianceAndCombination,
            @JsonProperty("tooth_targets") @NotNull JsonNode toothTargets,
            @JsonProperty("plan_parameters") @NotNull JsonNode planParameters,
            @JsonProperty("preview_and_submission") @NotNull JsonNode previewAndSubmission,
            @JsonProperty("submit") Boolean submit,
            @JsonProperty("expected_lock_version") @Min(0) Integer expectedLockVersion) {
    }

    public record CreatePlanVersionRequest(
            @JsonProperty("plan_file_id") Long planFileId,
            @JsonProperty("plan_snapshot") @NotNull JsonNode planSnapshot,
            @JsonProperty("design_note") @Size(max = 2000) String designNote) {
    }

    public record ReviewPlanRequest(
            @JsonProperty("decision")
                    @NotBlank @Pattern(regexp = "APPROVE|REJECT")
                    String decision,
            @JsonProperty("reason") @Size(max = 1000) String reason) {
    }

    public record CreateProductionBatchRequest(
            @JsonProperty("plan_version_id") @NotNull Long planVersionId,
            @JsonProperty("step_from") @NotNull @Min(1) Integer stepFrom,
            @JsonProperty("step_to") @NotNull @Min(1) Integer stepTo) {
    }

    public record CreateChangeRequest(
            @JsonProperty("source_plan_version_id") @NotNull Long sourcePlanVersionId,
            @JsonProperty("source_batch_id") Long sourceBatchId,
            @JsonProperty("request_type")
                    @NotBlank @Pattern(regexp = "STAGE_ADJUSTMENT|FOLLOW_UP_PROCESSING")
                    String requestType,
            @JsonProperty("reason") @NotBlank @Size(max = 2000) String reason) {
    }
}
