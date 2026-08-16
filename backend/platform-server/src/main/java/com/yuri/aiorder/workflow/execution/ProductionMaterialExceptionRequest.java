package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record ProductionMaterialExceptionRequest(
        @JsonProperty("exception_no") @NotBlank String exceptionNo,
        @JsonProperty("material_code") @NotBlank String materialCode,
        @JsonProperty("material_name") @NotBlank String materialName,
        @JsonProperty("order_id") Long orderId,
        @JsonProperty("node_instance_id") Long nodeInstanceId,
        @JsonProperty("exception_type") @NotBlank String exceptionType,
        String status,
        @JsonProperty("responsibility_owner") String responsibilityOwner,
        @JsonProperty("loss_quantity") BigDecimal lossQuantity,
        String description) {
}
