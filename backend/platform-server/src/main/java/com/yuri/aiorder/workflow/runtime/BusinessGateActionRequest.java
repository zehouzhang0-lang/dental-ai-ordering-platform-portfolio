package com.yuri.aiorder.workflow.runtime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BusinessGateActionRequest(
        @NotBlank
        @Size(max = 500)
        String note) {
}
