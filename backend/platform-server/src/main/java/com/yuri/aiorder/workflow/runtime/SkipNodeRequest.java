package com.yuri.aiorder.workflow.runtime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SkipNodeRequest(@NotBlank @Size(max = 255) String reason) {
}
