package com.yuri.aiorder.workflow.runtime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProductionQuestionRequest(
        @NotBlank @Size(max = 1000) String content) {
}
