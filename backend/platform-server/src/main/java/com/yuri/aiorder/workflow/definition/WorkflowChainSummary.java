package com.yuri.aiorder.workflow.definition;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WorkflowChainSummary(
        @JsonProperty("chain_id") long chainId,
        @JsonProperty("chain_name") String chainName,
        @JsonProperty("product_type") String productType,
        @JsonProperty("intake_branch") String intakeBranch,
        int status) {
}
