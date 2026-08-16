package com.yuri.aiorder.workflow.standardtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

public final class WorkflowStandardTimeModels {

    private WorkflowStandardTimeModels() {
    }

    public record CopyVersionRequest(
            @JsonProperty("source_version_id") Long sourceVersionId,
            @JsonProperty("version_name") @NotBlank @Size(max = 128) String versionName) {
    }

    public record StandardTimeItemUpdate(
            @JsonProperty("node_id") @NotNull Long nodeId,
            @JsonProperty("standard_duration_minutes")
                    @Min(0)
                    @Max(43200)
                    Integer standardDurationMinutes,
            @JsonProperty("status") @NotBlank String status,
            @JsonProperty("lock_version") @NotNull @Min(0) Integer lockVersion) {
    }

    public record BulkUpdateRequest(
            @JsonProperty("items") @NotNull @Valid List<StandardTimeItemUpdate> items,
            @JsonProperty("reason") @NotBlank @Size(max = 512) String reason) {
    }

    public record PublishRequest(
            @JsonProperty("effective_at") LocalDateTime effectiveAt,
            @JsonProperty("reason") @NotBlank @Size(max = 512) String reason,
            @JsonProperty("lock_version") @NotNull @Min(0) Integer lockVersion) {
    }

    public record VersionResponse(
            @JsonProperty("standard_time_version_id") long standardTimeVersionId,
            @JsonProperty("version_no") int versionNo,
            @JsonProperty("version_name") String versionName,
            @JsonProperty("publication_status") String publicationStatus,
            @JsonProperty("effective_at") LocalDateTime effectiveAt,
            @JsonProperty("lock_version") int lockVersion,
            @JsonProperty("formal_standard_time_enabled") boolean formalStandardTimeEnabled) {
    }

    public record NodeTimeResponse(
            @JsonProperty("standard_time_item_id") long standardTimeItemId,
            @JsonProperty("chain_id") long chainId,
            @JsonProperty("chain_code") String chainCode,
            @JsonProperty("chain_name") String chainName,
            @JsonProperty("product_type") String productType,
            @JsonProperty("node_id") long nodeId,
            @JsonProperty("node_code") String nodeCode,
            @JsonProperty("process_name") String processName,
            @JsonProperty("stage_name") String stageName,
            @JsonProperty("step_order") int stepOrder,
            @JsonProperty("standard_duration_minutes") Integer standardDurationMinutes,
            @JsonProperty("status") String status,
            @JsonProperty("lock_version") int lockVersion) {
    }
}
