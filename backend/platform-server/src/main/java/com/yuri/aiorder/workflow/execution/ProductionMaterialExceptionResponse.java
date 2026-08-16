package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record ProductionMaterialExceptionResponse(
        @JsonProperty("exception_id") long exceptionId,
        @JsonProperty("exception_no") String exceptionNo,
        @JsonProperty("material_code") String materialCode,
        @JsonProperty("material_name") String materialName,
        @JsonProperty("order_id") Long orderId,
        @JsonProperty("node_instance_id") Long nodeInstanceId,
        @JsonProperty("exception_type") String exceptionType,
        String status,
        @JsonProperty("responsibility_owner") String responsibilityOwner,
        @JsonProperty("loss_quantity") double lossQuantity,
        String description,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt,
        @JsonProperty("closed_at") LocalDateTime closedAt) {
}
