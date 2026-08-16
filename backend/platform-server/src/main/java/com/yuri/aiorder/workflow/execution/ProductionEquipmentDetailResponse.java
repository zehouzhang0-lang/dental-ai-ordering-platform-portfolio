package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ProductionEquipmentDetailResponse(
        ProductionEquipmentResponse equipment,
        @JsonProperty("events") List<ProductionEquipmentEventResponse> events) {
}
