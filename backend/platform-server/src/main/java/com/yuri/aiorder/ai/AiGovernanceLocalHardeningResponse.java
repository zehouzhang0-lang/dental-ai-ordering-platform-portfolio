package com.yuri.aiorder.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record AiGovernanceLocalHardeningResponse(
        @JsonProperty("stage_goal") String stageGoal,
        @JsonProperty("stage_task") String stageTask,
        @JsonProperty("prompt_templates") List<PromptTemplate> promptTemplates,
        @JsonProperty("output_safety_boundary") OutputSafetyBoundary outputSafetyBoundary,
        @JsonProperty("budget_circuit_breaker_policy") BudgetCircuitBreakerPolicy budgetCircuitBreakerPolicy,
        @JsonProperty("ai3_safety_cases") List<Ai3SafetyCase> ai3SafetyCases,
        @JsonProperty("ai5_template_boundary") Ai5TemplateBoundary ai5TemplateBoundary,
        @JsonProperty("real_external_integration_status") RealExternalIntegrationStatus realExternalIntegrationStatus) {

    public record PromptTemplate(
            @JsonProperty("agent_code") String agentCode,
            @JsonProperty("prompt_version") String promptVersion,
            @JsonProperty("context_type") String contextType,
            @JsonProperty("owner_role") String ownerRole,
            @JsonProperty("template_source") String templateSource,
            @JsonProperty("mutation_allowed") boolean mutationAllowed,
            @JsonProperty("human_confirmation_required") boolean humanConfirmationRequired) {
    }

    public record OutputSafetyBoundary(
            @JsonProperty("guarded_status") String guardedStatus,
            @JsonProperty("guarded_model_name") String guardedModelName,
            @JsonProperty("streaming_status") String streamingStatus,
            @JsonProperty("blocked_pattern_count") int blockedPatternCount,
            @JsonProperty("raw_model_output_exposed") boolean rawModelOutputExposed,
            @JsonProperty("manual_review_required") boolean manualReviewRequired) {
    }

    public record BudgetCircuitBreakerPolicy(
            @JsonProperty("daily_budget_microusd") long dailyBudgetMicrousd,
            @JsonProperty("admin_daily_budget_microusd") long adminDailyBudgetMicrousd,
            @JsonProperty("cs_daily_budget_microusd") long csDailyBudgetMicrousd,
            @JsonProperty("doctor_daily_budget_microusd") long doctorDailyBudgetMicrousd,
            @JsonProperty("worker_daily_budget_microusd") long workerDailyBudgetMicrousd,
            @JsonProperty("model_daily_budget_microusd") long modelDailyBudgetMicrousd,
            @JsonProperty("budget_notification_enabled") boolean budgetNotificationEnabled,
            @JsonProperty("budget_circuit_breaker_enabled") boolean budgetCircuitBreakerEnabled) {
    }

    public record Ai3SafetyCase(
            @JsonProperty("case_id") String caseId,
            @JsonProperty("question_family") String questionFamily,
            @JsonProperty("expected_status") String expectedStatus,
            @JsonProperty("safe_read_model") String safeReadModel,
            @JsonProperty("forbidden_fields") List<String> forbiddenFields) {
    }

    public record Ai5TemplateBoundary(
            @JsonProperty("template_version") String templateVersion,
            @JsonProperty("customer_template_status") String customerTemplateStatus,
            @JsonProperty("requires_customer_template_confirmation") boolean requiresCustomerTemplateConfirmation,
            @JsonProperty("auto_write_allowed") boolean autoWriteAllowed,
            @JsonProperty("human_confirmation_required") boolean humanConfirmationRequired) {
    }

    public record RealExternalIntegrationStatus(
            @JsonProperty("integration_status") String integrationStatus,
            @JsonProperty("deepseek_key_status") String deepseekKeyStatus,
            @JsonProperty("webhook_status") String webhookStatus,
            @JsonProperty("customer_signature_status") String customerSignatureStatus,
            @JsonProperty("task8_status") String task8Status) {
    }
}
