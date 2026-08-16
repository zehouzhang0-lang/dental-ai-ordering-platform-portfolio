package com.yuri.aiorder.workflow.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProductionReviewRequest(
        @NotBlank @Size(max = 32) String action,
        @JsonProperty("chain_id") Long chainId,
        @JsonProperty("intake_branch") @Size(max = 32) String intakeBranch,
        @JsonProperty("branch_params") JsonNode branchParams,
        @JsonProperty("reject_reason") @Size(max = 500) String rejectReason) {
}
