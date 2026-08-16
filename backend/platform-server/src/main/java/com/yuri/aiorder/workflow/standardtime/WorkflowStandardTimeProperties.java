package com.yuri.aiorder.workflow.standardtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.workflow.standard-time")
public record WorkflowStandardTimeProperties(boolean formalEnabled) {
}
