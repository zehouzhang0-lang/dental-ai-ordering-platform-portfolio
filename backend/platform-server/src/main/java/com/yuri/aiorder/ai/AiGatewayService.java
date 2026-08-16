package com.yuri.aiorder.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.AccessControlService;
import com.yuri.aiorder.file.api.FileResourceService;
import com.yuri.aiorder.file.api.FileSignedUrlResponse;
import com.yuri.aiorder.notification.NotificationPushService;
import com.yuri.aiorder.order.api.DoctorOrderAssistantReadModel;
import com.yuri.aiorder.order.api.OrderProjectionQueryService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AiGatewayService {

    private static final String DETERMINISTIC_MODEL_NAME = "deterministic-placeholder";
    private static final String PRODUCTION_NOTE_TEMPLATE_VERSION = "PHASE_ONE_DEFAULT_V1";
    private static final String LANGCHAIN_DEEPSEEK_PROVIDER = "LANGCHAIN_DEEPSEEK";
    private static final String RATE_LIMIT_MODEL_NAME = "ai-governance-rate-limit";
    private static final String RATE_LIMIT_STATUS = "AI_RATE_LIMITED";
    private static final String MODEL_FAILURE_MODEL_NAME = "ai-governance-model-failure";
    private static final String MODEL_FAILURE_STATUS = "AI_MODEL_FAILED";
    private static final String BUDGET_EXCEEDED_MODEL_NAME = "ai-governance-budget-exceeded";
    private static final String BUDGET_EXCEEDED_STATUS = "AI_BUDGET_EXCEEDED";
    private static final String BUDGET_CIRCUIT_OPEN_MODEL_NAME = "ai-governance-budget-circuit-open";
    private static final String BUDGET_CIRCUIT_OPEN_STATUS = "AI_BUDGET_CIRCUIT_OPEN";
    private static final String BUDGET_ROLE_CIRCUIT_OPEN_MODEL_NAME = "ai-governance-budget-role-circuit-open";
    private static final String BUDGET_ROLE_CIRCUIT_OPEN_STATUS = "AI_BUDGET_ROLE_CIRCUIT_OPEN";
    private static final String BUDGET_MODEL_CIRCUIT_OPEN_MODEL_NAME = "ai-governance-budget-model-circuit-open";
    private static final String BUDGET_MODEL_CIRCUIT_OPEN_STATUS = "AI_BUDGET_MODEL_CIRCUIT_OPEN";
    private static final String OUTPUT_GUARD_MODEL_NAME = "ai-governance-output-guard";
    private static final String OUTPUT_GUARD_STATUS = "AI_OUTPUT_GUARDED";
    private static final String GUARDED_STREAMING_NOT_ENABLED = "GUARDED_STREAMING_NOT_ENABLED";
    private static final String CUSTOMER_TEMPLATE_UNCONFIRMED = "CUSTOMER_TEMPLATE_UNCONFIRMED";
    private static final String REAL_EXTERNAL_INTEGRATION_PENDING = "REAL_EXTERNAL_INTEGRATION_PENDING";
    private static final String AI3_DOCTOR_INTERNAL_SAFETY_MATRIX = "AI3_DOCTOR_INTERNAL_SAFETY_MATRIX";
    private static final String EXTERNAL_ALERT_CHANNEL = "EXTERNAL_ALERT";
    private static final String EXTERNAL_ALERT_PENDING_STATUS = "PENDING";
    private static final String EXTERNAL_ALERT_SENDING_STATUS = "SENDING";
    private static final String EXTERNAL_ALERT_SENT_STATUS = "SENT";
    private static final String EXTERNAL_ALERT_FAILED_STATUS = "FAILED";
    private static final String EXTERNAL_ALERT_DEAD_LETTER_STATUS = "DEAD_LETTER";
    private static final String SAMPLE_PENDING_CUSTOMER_CONFIRMATION = "SAMPLE_PENDING_CUSTOMER_CONFIRMATION";
    private static final String AI_FAQ_SOURCE_NOTE = "AI-6 只依据知识库条目作答；示例语料待甲方确认（CP-013）。";
    private static final String AI_PRODUCT_RECOMMENDATION_SOURCE_NOTE =
            "AI-7 推荐仅供参考，医生需自行确认；候选来自当前生效的产品目录版本，价格以正式报价为准。";
    private static final int FAQ_CONTEXT_LIMIT = 5;
    private static final int PRODUCT_RECOMMENDATION_LIMIT = 3;
    private static final int PRODUCT_CANDIDATE_LIMIT = 30;
    private static final String RECOMMENDED_IDS_MARKER = "RECOMMENDED_IDS:";
    private static final List<Map.Entry<String, String>> CUSTOMER_REQUIREMENT_CATEGORIES = List.of(
            Map.entry("contact", "邻接"),
            Map.entry("occlusion", "咬合"),
            Map.entry("color", "颜色"),
            Map.entry("material", "材料"),
            Map.entry("margin", "边缘"),
            Map.entry("shape", "形态"),
            Map.entry("note", "其他要求"));
    private static final List<String> OUTPUT_GUARD_PATTERNS = List.of(
            "deepseek_api_key",
            "app_auth_token_secret",
            "minio_secret_key",
            "api key",
            "secret=",
            "password=",
            "token=",
            "内部工序备注：不要泄露",
            "file_resource",
            "ai_audit_log",
            "auth_refresh_token",
            "system_user");
    private static final List<String> DOCTOR_INTERNAL_KEYWORDS = List.of(
            "工序", "员工", "技工", "谁在做", "返工", "工时", "绩效", "入检", "出检",
            "责任", "internal", "process", "work_log", "rework", "performance", "assigned");

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final OrderProjectionQueryService orderProjectionQueryService;
    private final AccessControlService accessControlService;
    private final FileResourceService fileResourceService;
    private final AiModelClient aiModelClient;
    private final AiGatewayProperties properties;
    private final NotificationPushService notificationPushService;
    private final TransactionTemplate aiGovernanceAuditTransaction;

    public AiGatewayService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            OrderProjectionQueryService orderProjectionQueryService,
            AccessControlService accessControlService,
            FileResourceService fileResourceService,
            AiModelClient aiModelClient,
            AiGatewayProperties properties,
            NotificationPushService notificationPushService,
            PlatformTransactionManager transactionManager) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.orderProjectionQueryService = orderProjectionQueryService;
        this.accessControlService = accessControlService;
        this.fileResourceService = fileResourceService;
        this.aiModelClient = aiModelClient;
        this.properties = properties;
        this.notificationPushService = notificationPushService;
        this.aiGovernanceAuditTransaction = new TransactionTemplate(transactionManager);
        this.aiGovernanceAuditTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public String translate(long orderId, String sourceText, BootstrapIdentity identity) {
        accessControlService.requirePermission(identity, "ai:cs", "AI-1 requires ai:cs");
        OrderAiContext context = loadOrderContext(orderId, identity, "identity cannot access this order");
        enforceAiRateLimit(orderId, identity, "AI_TRANSLATE", "ORDER_TRANSLATION_DRAFT", sourceText);
        AiModelResult answer = completeWithModel(
                "你是牙科工厂客服翻译助手。只输出翻译草稿，不自动审核、不自动发送。",
                "订单号：" + context.orderNo() + "\n待翻译内容：" + sourceText.trim(),
                () -> deterministic("翻译草稿（需客服确认后才可写入订单）："
                        + sourceText.trim()
                        + "。订单号："
                        + context.orderNo()
                        + "。"),
                orderId,
                identity,
                "AI_TRANSLATE",
                "ORDER_TRANSLATION_DRAFT",
                sourceText);
        audit(orderId, identity, "AI_TRANSLATE", "ORDER_TRANSLATION_DRAFT", sourceText, "SUCCESS", answer);
        return answer.content();
    }

    @Transactional
    public CsQueryResult csQuery(long orderId, String question, BootstrapIdentity identity) {
        accessControlService.requirePermission(identity, "ai:cs", "AI-2 requires ai:cs");
        OrderAiContext context = loadOrderContext(orderId, identity, "identity cannot access this order");
        List<String> referenceDataNotes = buildCsReferenceDataNotes(context);
        List<CsAttachmentContext> attachmentContexts = buildCsAttachmentContexts(context.orderId(), identity);
        String referenceDataText = String.join("\n", referenceDataNotes);
        String attachmentContextText = attachmentContexts.isEmpty()
                ? "消息附件预览：当前未聚合到可预览附件。"
                : "消息附件预览：\n" + attachmentContextText(attachmentContexts);
        enforceAiRateLimit(orderId, identity, "AI_CS_QUERY", "INTERNAL_ORDER_SUMMARY", question);
        AiModelResult answer = completeWithModel(
                "你是牙科工厂客服查询助手。可以辅助客服理解内部订单摘要，但输出必须提示人工确认。",
                "订单号：" + context.orderNo()
                        + "\n产品类型：" + context.productType()
                        + "\n内部状态：" + context.internalStatus()
                        + "\n外部状态：" + context.externalStatus()
                        + "\n生产备注：" + nullToBlank(context.productionNote())
                        + "\n引用数据说明：\n" + referenceDataText
                        + "\n" + attachmentContextText
                        + "\n客服问题：" + question,
                () -> deterministic("客服查询草稿：订单"
                        + context.orderNo()
                        + "内部状态为"
                        + context.internalStatus()
                        + "，外部状态为"
                        + context.externalStatus()
                        + "。引用数据包括：" + String.join("；", referenceDataNotes)
                        + (attachmentContexts.isEmpty()
                                ? ""
                                : "。消息附件预览已聚合 " + attachmentContexts.size() + " 个，需客服人工复核")
                        + "。对外发送前需人工确认。"),
                orderId,
                identity,
                "AI_CS_QUERY",
                "INTERNAL_ORDER_SUMMARY",
                question);
        audit(orderId, identity, "AI_CS_QUERY", "INTERNAL_ORDER_SUMMARY", question, "SUCCESS", answer);
        return new CsQueryResult(answer.content(), referenceDataNotes, attachmentContexts);
    }

    @Transactional
    public String orderQuery(long orderId, String question, BootstrapIdentity identity) {
        accessControlService.requireDoctorPortalAction(identity, "ai:doctor", "AI-3 requires ai:doctor");
        DoctorOrderAssistantReadModel readModel = orderProjectionQueryService.getAssistantReadModel(orderId, identity);
        boolean internalQuestion = asksForInternalData(question);
        String answer;
        String resultStatus;
        if (internalQuestion) {
            answer = "我只能回答公开进度、账单和物流信息。您的订单当前公开状态："
                    + readModel.externalStatus()
                    + publicSuffix(readModel)
                    + "。";
            resultStatus = "SAFE_REFUSAL";
            audit(orderId, identity, "AI_DOCTOR_ORDER_QUERY", "DOCTOR_ORDER_ASSISTANT_READ_MODEL", question,
                    resultStatus, deterministic(answer));
            return answer;
        } else {
            enforceAiRateLimit(orderId, identity, "AI_DOCTOR_ORDER_QUERY", "DOCTOR_ORDER_ASSISTANT_READ_MODEL", question);
            AiModelResult aiAnswer = completeWithModel(
                    "你是医生端订单助手。只能回答公开进度、账单、物流和医生可见消息；不得推测内部工序、员工、返工、工时或绩效。",
                    "公开状态：" + readModel.externalStatus()
                            + "\n公开信息：" + publicSuffix(readModel)
                            + "\n医生问题：" + question,
                    () -> deterministic("您的订单当前状态："
                            + readModel.externalStatus()
                            + publicSuffix(readModel)
                            + "。"),
                    orderId,
                    identity,
                    "AI_DOCTOR_ORDER_QUERY",
                    "DOCTOR_ORDER_ASSISTANT_READ_MODEL",
                    question);
            answer = aiAnswer.content();
            resultStatus = "SUCCESS";
            audit(orderId, identity, "AI_DOCTOR_ORDER_QUERY", "DOCTOR_ORDER_ASSISTANT_READ_MODEL", question,
                    resultStatus, aiAnswer);
            return answer;
        }
    }

    /**
     * AI-6 牙科 FAQ。不依附具体订单，因此审计链路的 orderId 为空。
     *
     * <p>模型只允许基于命中的知识条目作答；命中不到时不外呼模型，直接返回引导联系客服的兜底话术。
     * 医生问到内部工序 / 员工 / 返工 / 工时 / 绩效时，继续走 AI-3 的安全拒答边界，不因新增入口而放宽。
     */
    @Transactional
    public AiFaqResponse faq(String question, String category, BootstrapIdentity identity) {
        accessControlService.requireAnyPermission(identity, "AI-6 requires ai:doctor or ai:cs", "ai:doctor", "ai:cs");
        String normalizedQuestion = question == null ? "" : question.trim();
        if (normalizedQuestion.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        }
        if (identity.role() == UserRole.DOCTOR && asksForInternalData(normalizedQuestion)) {
            String refusal = "我只能回答下单流程、产品材料、交期物流、返工售后和账单方面的常见问题，"
                    + "内部工序、技师、返工和绩效信息需要联系客服。";
            audit(null, identity, "AI_FAQ", "AI_FAQ_KNOWLEDGE_BASE", normalizedQuestion,
                    "SAFE_REFUSAL", deterministic(refusal));
            return new AiFaqResponse(refusal, List.of(), "SAFE_REFUSAL", false, AI_FAQ_SOURCE_NOTE);
        }

        List<FaqEntry> entries = matchFaqEntries(normalizedQuestion, category, identity);
        List<AiFaqResponse.MatchedEntry> matchedEntries = entries.stream()
                .map(entry -> new AiFaqResponse.MatchedEntry(
                        entry.faqId(), entry.category(), entry.question(), entry.sourceNote()))
                .toList();
        boolean requiresCustomerConfirmation = entries.stream()
                .anyMatch(entry -> SAMPLE_PENDING_CUSTOMER_CONFIRMATION.equals(entry.sourceNote()));

        if (entries.isEmpty()) {
            String noMatch = "这个问题暂时不在常见问题库里，请通过沟通中心联系客服，我们会安排人工回复。";
            audit(null, identity, "AI_FAQ", "AI_FAQ_KNOWLEDGE_BASE", normalizedQuestion,
                    "NO_MATCH", deterministic(noMatch));
            return new AiFaqResponse(noMatch, List.of(), "NO_MATCH", false, AI_FAQ_SOURCE_NOTE);
        }

        String knowledgeContext = entries.stream()
                .map(entry -> "【" + entry.category() + "】" + entry.question() + "\n答：" + entry.answer())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
        enforceAiRateLimit(null, identity, "AI_FAQ", "AI_FAQ_KNOWLEDGE_BASE", normalizedQuestion);
        AiModelResult answer = completeWithModel(
                "你是牙科加工厂的常见问题助手。只能依据下面给出的知识条目作答，不得编造条目之外的信息，"
                        + "不得给出诊疗建议，也不得透露内部工序、技师、返工、工时或绩效。"
                        + "知识条目无法回答时，请直接说明需要联系客服。",
                "知识条目：\n" + knowledgeContext + "\n\n提问：" + normalizedQuestion,
                () -> deterministic(entries.get(0).answer()),
                null,
                identity,
                "AI_FAQ",
                "AI_FAQ_KNOWLEDGE_BASE",
                normalizedQuestion);
        audit(null, identity, "AI_FAQ", "AI_FAQ_KNOWLEDGE_BASE", normalizedQuestion, "SUCCESS", answer);
        return new AiFaqResponse(
                answer.content(), matchedEntries, "SUCCESS", requiresCustomerConfirmation, AI_FAQ_SOURCE_NOTE);
    }

    /**
     * AI-7 智能推荐产品。候选集只来自当前生效的产品目录版本，依据是该诊所的历史下单分布与病例描述。
     *
     * <p>结果只是下单向导中的建议项；医生必须显式选择才生效，系统不会自动填表。
     */
    @Transactional
    public AiProductRecommendationResponse recommendProducts(
            Long clinicId, String caseNote, BootstrapIdentity identity) {
        accessControlService.requireAnyPermission(
                identity, "AI-7 requires ai:doctor or ai:cs", "ai:doctor", "ai:cs");
        Long targetClinicId = identity.role() == UserRole.DOCTOR ? identity.clinicId() : clinicId;
        if (identity.role() == UserRole.DOCTOR && targetClinicId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clinic id is required for doctor recommendation");
        }
        String normalizedCaseNote = caseNote == null ? "" : caseNote.trim();

        List<CatalogCandidate> candidates = loadRecommendationCandidates();
        if (candidates.isEmpty()) {
            return new AiProductRecommendationResponse(
                    List.of(),
                    List.of(),
                    null,
                    "当前没有已发布的产品目录版本，无法给出推荐。",
                    AI_PRODUCT_RECOMMENDATION_SOURCE_NOTE);
        }
        List<AiProductRecommendationResponse.ClinicHistoryItem> history = loadClinicProductHistory(targetClinicId);

        String candidateText = candidates.stream()
                .map(candidate -> "- [" + candidate.productId() + "] " + candidate.displayName()
                        + "（分类：" + candidate.categoryName()
                        + "；工艺类型：" + nullToBlank(candidate.workflowProductType())
                        + "；定价状态：" + candidate.pricingStatus() + "）")
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        String historyText = history.isEmpty()
                ? "该诊所暂无历史下单记录。"
                : history.stream()
                        .map(item -> item.productType() + " " + item.orderCount() + " 单")
                        .reduce((left, right) -> left + "；" + right)
                        .orElse("");

        enforceAiRateLimit(null, identity, "AI_PRODUCT_RECOMMENDATION", "CATALOG_AND_CLINIC_HISTORY", normalizedCaseNote);
        AiModelResult answer = completeWithModel(
                "你是牙科加工厂的下单建议助手。只能从给定的候选产品中挑选，不得编造产品。"
                        + "针对每个推荐给出一句话依据，依据必须来自候选产品信息、诊所历史下单分布或病例描述。"
                        + "最多推荐 3 个，并说明这是建议、需要医生自行确认。"
                        + "最后必须单独用一行输出机器可读结果，格式为 RECOMMENDED_IDS: 逗号分隔的候选产品编号，"
                        + "编号必须来自候选列表方括号中的数字。",
                "候选产品：\n" + candidateText
                        + "\n\n该诊所历史下单分布：" + historyText
                        + "\n\n病例描述：" + (normalizedCaseNote.isEmpty() ? "医生未填写描述。" : normalizedCaseNote),
                () -> deterministic(deterministicRecommendationNote(candidates, history)),
                null,
                identity,
                "AI_PRODUCT_RECOMMENDATION",
                "CATALOG_AND_CLINIC_HISTORY",
                normalizedCaseNote);
        audit(null, identity, "AI_PRODUCT_RECOMMENDATION", "CATALOG_AND_CLINIC_HISTORY", normalizedCaseNote,
                "SUCCESS", answer);

        List<AiProductRecommendationResponse.Recommendation> recommendations =
                rankRecommendations(candidates, history, parseRecommendedProductIds(answer.content(), candidates));
        return new AiProductRecommendationResponse(
                recommendations,
                history,
                candidates.get(0).configVersionId(),
                stripRecommendedIdsLine(answer.content()),
                AI_PRODUCT_RECOMMENDATION_SOURCE_NOTE);
    }

    @Transactional
    public MissingInfoResponse checkMissing(long orderId, BootstrapIdentity identity) {
        accessControlService.requireAnyPermission(identity, "AI-4 requires ai:doctor or ai:cs", "ai:doctor", "ai:cs");
        OrderAiContext context = loadOrderContext(orderId, identity, "doctor cannot access this order");

        JsonNode formData = orderProjectionQueryService.getNormalizedFormData(orderId, identity);
        JsonNode formValues = formData.path("form_values").isObject()
                ? formData.path("form_values")
                : formData;
        List<MissingInfoResponse.MissingItem> missingItems = requiredFields(orderId, context.productType()).stream()
                .filter(field -> field.visible(formValues))
                .filter(field -> isMissing(formValues.get(field.fieldKey())))
                .map(field -> new MissingInfoResponse.MissingItem(
                        field.fieldKey(),
                        field.fieldLabel(),
                        "缺少" + field.fieldLabel() + "，请补充。"))
                .toList();
        audit(orderId, identity, "AI_CHECK_MISSING", "ORDER_FORM_REQUIRED_FIELDS", "check-missing:" + orderId,
                "SUCCESS", deterministic("missing-info-rule"));
        return new MissingInfoResponse(missingItems.isEmpty(), missingItems);
    }

    @Transactional
    public ProductionNoteDraftResult productionNote(long orderId, BootstrapIdentity identity) {
        accessControlService.requireAnyPermission(identity, "AI-5 requires ai:production or ai:cs", "ai:production", "ai:cs");
        OrderAiContext context = loadOrderContext(orderId, identity, "identity cannot access this order");
        JsonNode normalizedFormData = orderProjectionQueryService.getNormalizedFormData(orderId, identity);
        Map<String, String> customerRequirements = loadCustomerProductionRequirements(context.clinicId());
        List<String> knowledgeContextNotes = buildProductionNoteKnowledgeContextNotes(context, customerRequirements);
        String businessDraftBaseline = defaultProductionNoteDraft(normalizedFormData, customerRequirements);
        enforceAiRateLimit(orderId, identity, "AI_PRODUCTION_NOTE", "PRODUCTION_NOTE_DRAFT",
                "production-note:" + orderId);
        AiModelResult draft = completeWithModel(
                "你是牙科生产信息整理助手。只输出生产人员可直接执行的中文制作要求草稿。"
                        + "必须保留客户档案中已维护的特殊要求，不得自行改变数值或松紧方向。"
                        + "严禁输出模板版本、数据库字段、数据来源、知识上下文、内部状态、审计说明或 AI 说明。"
                        + "不写入订单字段、不自动下发生产指令，最终内容必须由客服人工确认。",
                "订单业务资料：" + normalizedFormData
                        + "\n客户档案特殊要求：" + customerRequirementSummary(customerRequirements)
                        + "\n业务草稿基线：\n" + businessDraftBaseline
                        + "\n请只整理上述业务内容；没有明确要求的项目不要猜测或补写。",
                () -> deterministic(businessDraftBaseline),
                orderId,
                identity,
                "AI_PRODUCTION_NOTE",
                "PRODUCTION_NOTE_DRAFT",
                "production-note:" + orderId);
        audit(orderId, identity, "AI_PRODUCTION_NOTE", "PRODUCTION_NOTE_DRAFT", "production-note:" + orderId,
                "SUCCESS", draft);
        return new ProductionNoteDraftResult(
                draft.content(),
                PRODUCTION_NOTE_TEMPLATE_VERSION,
                knowledgeContextNotes,
                true);
    }

    @Transactional
    public ProductionNoteConfirmationResult confirmProductionNote(
            long orderId,
            String draftNote,
            String confirmationNote,
            BootstrapIdentity identity) {
        accessControlService.requireAnyPermission(identity, "AI-5 confirmation requires ai:production or ai:cs", "ai:production", "ai:cs");
        OrderAiContext context = loadOrderContext(orderId, identity, "identity cannot access this order");
        String trimmedDraft = draftNote == null ? "" : draftNote.trim();
        if (trimmedDraft.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "draft_note is required");
        }
        String confirmedBlock = confirmedProductionNoteBlock(trimmedDraft, confirmationNote, identity);
        String existing = nullToBlank(context.productionNote()).trim();
        String updatedNote = existing.isBlank() ? confirmedBlock : existing + "\n\n" + confirmedBlock;
        jdbcClient.sql("""
                        UPDATE orders
                        SET production_note = :productionNote
                        WHERE order_id = :orderId
                        """)
                .param("productionNote", updatedNote)
                .param("orderId", orderId)
                .update();
        audit(orderId, identity, "AI_PRODUCTION_NOTE_CONFIRM", "PRODUCTION_NOTE_HUMAN_CONFIRMED",
                trimmedDraft, "SUCCESS", deterministic("production-note-human-confirmed"));
        return new ProductionNoteConfirmationResult(updatedNote, PRODUCTION_NOTE_TEMPLATE_VERSION, true);
    }

    @Transactional(readOnly = true)
    public AiGovernanceSummaryResponse governanceSummary(BootstrapIdentity identity) {
        accessControlService.requirePermission(identity, "ai:governance:read", "AI governance summary requires ai:governance:read");
        long dailyBudgetMicrousd = Math.max(0, properties.getDailyBudgetMicrousd());
        return jdbcClient.sql("""
                        SELECT
                            COALESCE(SUM(CASE WHEN result_status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS success_count,
                            COALESCE(SUM(CASE WHEN result_status = 'SAFE_REFUSAL' THEN 1 ELSE 0 END), 0) AS safe_refusal_count,
                            COALESCE(SUM(CASE WHEN result_status = :rateLimitStatus THEN 1 ELSE 0 END), 0) AS rate_limited_count,
                            COALESCE(SUM(CASE WHEN result_status = :modelFailureStatus THEN 1 ELSE 0 END), 0) AS model_failed_count,
                            COALESCE(SUM(CASE WHEN result_status = :budgetExceededStatus THEN 1 ELSE 0 END), 0) AS budget_alert_count,
                            COALESCE(SUM(estimated_cost_microusd), 0) AS estimated_cost_microusd,
                            MAX(CASE WHEN result_status = :modelFailureStatus THEN created_at ELSE NULL END) AS latest_model_failure_at,
                            MAX(CASE WHEN result_status = :budgetExceededStatus THEN created_at ELSE NULL END) AS latest_budget_alert_at
                        FROM ai_audit_log
                        WHERE created_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 24 HOUR)
                        """)
                .param("rateLimitStatus", RATE_LIMIT_STATUS)
                .param("modelFailureStatus", MODEL_FAILURE_STATUS)
                .param("budgetExceededStatus", BUDGET_EXCEEDED_STATUS)
                .query((rs, rowNum) -> new AiGovernanceSummaryResponse(
                        24,
                        rs.getLong("success_count"),
                        rs.getLong("safe_refusal_count"),
                        rs.getLong("rate_limited_count"),
                        rs.getLong("model_failed_count"),
                        rs.getLong("estimated_cost_microusd"),
                        dailyBudgetMicrousd,
                        dailyBudgetMicrousd > 0 && rs.getLong("estimated_cost_microusd") >= dailyBudgetMicrousd,
                        rs.getLong("budget_alert_count"),
                        rs.getObject("latest_model_failure_at", LocalDateTime.class),
                        rs.getObject("latest_budget_alert_at", LocalDateTime.class)))
                .single();
    }

    @Transactional(readOnly = true)
    public AiGovernanceLocalHardeningResponse governanceLocalHardening(BootstrapIdentity identity) {
        accessControlService.requirePermission(identity, "ai:governance:read", "AI governance local hardening requires ai:governance:read");
        return new AiGovernanceLocalHardeningResponse(
                "GOAL-019",
                "TASK-020",
                promptVersionCatalog(),
                new AiGovernanceLocalHardeningResponse.OutputSafetyBoundary(
                        OUTPUT_GUARD_STATUS,
                        OUTPUT_GUARD_MODEL_NAME,
                        GUARDED_STREAMING_NOT_ENABLED,
                        OUTPUT_GUARD_PATTERNS.size(),
                        false,
                        true),
                new AiGovernanceLocalHardeningResponse.BudgetCircuitBreakerPolicy(
                        Math.max(0, properties.getDailyBudgetMicrousd()),
                        Math.max(0, properties.getAdminDailyBudgetMicrousd()),
                        Math.max(0, properties.getCsDailyBudgetMicrousd()),
                        Math.max(0, properties.getDoctorDailyBudgetMicrousd()),
                        Math.max(0, properties.getWorkerDailyBudgetMicrousd()),
                        Math.max(0, properties.getDeepseek().getDailyBudgetMicrousd()),
                        properties.isBudgetNotificationEnabled(),
                        properties.isBudgetCircuitBreakerEnabled()),
                ai3SafetyMatrix(),
                new AiGovernanceLocalHardeningResponse.Ai5TemplateBoundary(
                        PRODUCTION_NOTE_TEMPLATE_VERSION,
                        CUSTOMER_TEMPLATE_UNCONFIRMED,
                        true,
                        false,
                        true),
                new AiGovernanceLocalHardeningResponse.RealExternalIntegrationStatus(
                        REAL_EXTERNAL_INTEGRATION_PENDING,
                        "PENDING_EXTERNAL_KEY",
                        "PENDING_PRODUCTION_WEBHOOK",
                        "PENDING_CUSTOMER_PM_SIGNATURE",
                        "NOT_READY"));
    }

    @Transactional(readOnly = true)
    public AiGovernanceCostTrendResponse governanceCostTrend(BootstrapIdentity identity, int requestedDays) {
        accessControlService.requirePermission(identity, "ai:governance:read", "AI governance cost trend requires ai:governance:read");
        int days = Math.max(1, Math.min(31, requestedDays));
        List<AiGovernanceCostTrendResponse.Point> points = jdbcClient.sql("""
                        SELECT
                            cost_date,
                            COUNT(*) AS success_count,
                            COALESCE(SUM(estimated_cost_microusd), 0) AS estimated_cost_microusd,
                            COUNT(DISTINCT model_name) AS model_count
                        FROM (
                            SELECT
                                DATE_FORMAT(DATE(created_at), '%Y-%m-%d') AS cost_date,
                                model_name,
                                estimated_cost_microusd
                            FROM ai_audit_log
                            WHERE result_status = 'SUCCESS'
                              AND created_at >= DATE_SUB(CURRENT_DATE, INTERVAL :lookbackDays DAY)
                        ) daily_cost
                        GROUP BY cost_date
                        ORDER BY cost_date
                        """)
                .param("lookbackDays", days - 1)
                .query((rs, rowNum) -> new AiGovernanceCostTrendResponse.Point(
                        rs.getString("cost_date"),
                        rs.getLong("success_count"),
                        rs.getLong("estimated_cost_microusd"),
                        rs.getLong("model_count")))
                .list();
        long totalSuccessCount = points.stream()
                .mapToLong(AiGovernanceCostTrendResponse.Point::successCount)
                .sum();
        long totalEstimatedCostMicrousd = points.stream()
                .mapToLong(AiGovernanceCostTrendResponse.Point::estimatedCostMicrousd)
                .sum();
        return new AiGovernanceCostTrendResponse(days, points, totalSuccessCount, totalEstimatedCostMicrousd);
    }

    @Transactional(readOnly = true)
    public AiExternalAlertSummaryResponse externalAlertSummary(BootstrapIdentity identity) {
        accessControlService.requirePermission(identity, "ai:governance:read", "AI external alert summary requires ai:governance:read");
        List<AiExternalAlertSummaryResponse.StatusCount> statusCounts = jdbcClient.sql("""
                        SELECT send_status, COUNT(*) AS status_count
                        FROM ai_external_alert_outbox
                        GROUP BY send_status
                        ORDER BY send_status
                        """)
                .query((rs, rowNum) -> new AiExternalAlertSummaryResponse.StatusCount(
                        rs.getString("send_status"),
                        rs.getLong("status_count")))
                .list();
        AiExternalAlertSummaryResponse.Failure latestFailure = jdbcClient.sql("""
                        SELECT alert_id, alert_type, send_status, attempts, last_error, updated_at
                        FROM ai_external_alert_outbox
                        WHERE send_status IN (:failedStatus, :deadLetterStatus)
                        ORDER BY updated_at DESC, alert_id DESC
                        LIMIT 1
                        """)
                .param("failedStatus", EXTERNAL_ALERT_FAILED_STATUS)
                .param("deadLetterStatus", EXTERNAL_ALERT_DEAD_LETTER_STATUS)
                .query((rs, rowNum) -> new AiExternalAlertSummaryResponse.Failure(
                        rs.getLong("alert_id"),
                        rs.getString("alert_type"),
                        rs.getString("send_status"),
                        rs.getInt("attempts"),
                        sanitizeExternalAlertError(rs.getString("last_error")),
                        rs.getObject("updated_at", LocalDateTime.class)))
                .optional()
                .orElse(null);
        LocalDateTime oldestPendingCreatedAt = jdbcClient.sql("""
                        SELECT MIN(created_at)
                        FROM ai_external_alert_outbox
                        WHERE send_status = :pendingStatus
                        """)
                .param("pendingStatus", EXTERNAL_ALERT_PENDING_STATUS)
                .query(LocalDateTime.class)
                .optional()
                .orElse(null);
        return new AiExternalAlertSummaryResponse(
                statusCounts,
                countStatus(statusCounts, EXTERNAL_ALERT_PENDING_STATUS),
                countStatus(statusCounts, EXTERNAL_ALERT_SENDING_STATUS),
                countStatus(statusCounts, EXTERNAL_ALERT_SENT_STATUS),
                countStatus(statusCounts, EXTERNAL_ALERT_FAILED_STATUS),
                countStatus(statusCounts, EXTERNAL_ALERT_DEAD_LETTER_STATUS),
                latestFailure,
                oldestPendingCreatedAt);
    }

    @Transactional(readOnly = true)
    public AiExternalAlertListResponse externalAlerts(
            BootstrapIdentity identity,
            String sendStatus,
            String eventType,
            String createdAtFrom,
            String createdAtTo,
            int requestedLimit) {
        accessControlService.requirePermission(identity, "ai:governance:read", "AI external alert list requires ai:governance:read");
        int limit = Math.max(1, Math.min(100, requestedLimit));
        LocalDateTime from = parseNullableDateTime(createdAtFrom, "created_at_from");
        LocalDateTime to = parseNullableDateTime(createdAtTo, "created_at_to");
        String normalizedSendStatus = blankToNull(sendStatus);
        String normalizedEventType = blankToNull(eventType);
        List<AiExternalAlertListResponse.Record> records = jdbcClient.sql("""
                        SELECT alert_id, alert_type, send_status, attempts, last_error, created_at, updated_at
                        FROM ai_external_alert_outbox
                        WHERE (:sendStatus IS NULL OR send_status = :sendStatus)
                          AND (:eventType IS NULL OR alert_type = :eventType)
                          AND (:createdAtFrom IS NULL OR created_at >= :createdAtFrom)
                          AND (:createdAtTo IS NULL OR created_at <= :createdAtTo)
                        ORDER BY created_at DESC, alert_id DESC
                        LIMIT :limit
                        """)
                .param("sendStatus", normalizedSendStatus)
                .param("eventType", normalizedEventType)
                        .param("createdAtFrom", from)
                        .param("createdAtTo", to)
                        .param("limit", limit)
                .query((rs, rowNum) -> {
                    String rowSendStatus = rs.getString("send_status");
                    int attempts = rs.getInt("attempts");
                    LocalDateTime updatedAt = rs.getObject("updated_at", LocalDateTime.class);
                    return new AiExternalAlertListResponse.Record(
                            rs.getLong("alert_id"),
                            rs.getString("alert_type"),
                            rowSendStatus,
                            rs.getObject("created_at", LocalDateTime.class),
                            updatedAt,
                            attempts,
                            failedOrDeadLetter(rowSendStatus)
                                    ? sanitizeExternalAlertError(rs.getString("last_error"))
                                    : null,
                            attempts > 0 ? updatedAt : null);
                })
                .list();
        return new AiExternalAlertListResponse(limit, records);
    }

    private AiModelResult completeWithModel(
            String systemPrompt,
            String userPrompt,
            Supplier<AiModelResult> fallback,
            Long orderId,
            BootstrapIdentity identity,
            String agentCode,
            String contextType,
            String auditPrompt) {
        if (!aiModelClient.isEnabled()) {
            return fallback.get();
        }
        if (roleBudgetCircuitBreakerOpen(identity.role())) {
            auditBudgetRoleCircuitOpen(orderId, identity, agentCode, contextType, auditPrompt);
            return fallback.get();
        }
        if (modelBudgetCircuitBreakerOpen()) {
            auditBudgetModelCircuitOpen(orderId, identity, agentCode, contextType, auditPrompt);
            return fallback.get();
        }
        if (budgetCircuitBreakerOpen()) {
            auditBudgetCircuitOpen(orderId, identity, agentCode, contextType, auditPrompt);
            return fallback.get();
        }
        RuntimeException lastFailure = null;
        int maxAttempts = Math.max(1, properties.getMaxModelRetries() + 1);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                AiModelResult result = aiModelClient.complete(systemPrompt, userPrompt);
                if (outputGuardTriggered(result.content())) {
                    auditOutputGuarded(orderId, identity, agentCode, contextType, auditPrompt, result);
                    return deterministic("AI 输出已触发安全保护，请人工复核后再使用。");
                }
                return result;
            } catch (RuntimeException ex) {
                lastFailure = ex;
                if (attempt == maxAttempts || !isRetryableModelFailure(ex)) {
                    auditModelFailure(orderId, identity, agentCode, contextType, auditPrompt);
                    throw new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "AI model temporarily unavailable",
                            ex);
                }
            }
        }
        auditModelFailure(orderId, identity, agentCode, contextType, auditPrompt);
        throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AI model temporarily unavailable",
                lastFailure == null ? new IllegalStateException("AI model retry failed") : lastFailure);
    }

    private long countStatus(List<AiExternalAlertSummaryResponse.StatusCount> statusCounts, String sendStatus) {
        return statusCounts.stream()
                .filter(statusCount -> sendStatus.equals(statusCount.sendStatus()))
                .mapToLong(AiExternalAlertSummaryResponse.StatusCount::count)
                .findFirst()
                .orElse(0L);
    }

    private String sanitizeExternalAlertError(String error) {
        if (error == null || error.isBlank()) {
            return error;
        }
        String sanitized = error.replaceAll("(?i)(bearer\\s+)[^\\s]+", "$1[redacted]");
        sanitized = sanitized.replaceAll("(?i)sk-[a-z0-9._-]+", "[redacted-secret]");
        sanitized = sanitized.replaceAll("(?i)(token|secret|key|signature)=([^\\s&]+)", "$1=[redacted]");
        return sanitized.replaceAll("https?://\\S+", "[redacted-url]");
    }

    private boolean failedOrDeadLetter(String sendStatus) {
        return EXTERNAL_ALERT_FAILED_STATUS.equals(sendStatus)
                || EXTERNAL_ALERT_DEAD_LETTER_STATUS.equals(sendStatus);
    }

    private LocalDateTime parseNullableDateTime(String value, String fieldName) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(normalized);
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " must be ISO-8601 local datetime");
        }
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void auditOutputGuarded(
            Long orderId,
            BootstrapIdentity identity,
            String agentCode,
            String contextType,
            String prompt,
            AiModelResult modelResult) {
        aiGovernanceAuditTransaction.executeWithoutResult(status -> audit(
                orderId,
                identity,
                agentCode,
                contextType,
                prompt,
                OUTPUT_GUARD_STATUS,
                new AiModelResult(
                        "ai-output-guarded",
                        OUTPUT_GUARD_MODEL_NAME,
                        modelResult.inputTokenCount(),
                        modelResult.outputTokenCount())));
    }

    private void auditModelFailure(
            Long orderId,
            BootstrapIdentity identity,
            String agentCode,
            String contextType,
            String prompt) {
        aiGovernanceAuditTransaction.executeWithoutResult(status -> audit(
                orderId,
                identity,
                agentCode,
                contextType,
                prompt,
                MODEL_FAILURE_STATUS,
                new AiModelResult("ai-model-failed", MODEL_FAILURE_MODEL_NAME, 0, null)));
    }

    private boolean budgetCircuitBreakerOpen() {
        long dailyBudgetMicrousd = Math.max(0, properties.getDailyBudgetMicrousd());
        if (!properties.isBudgetCircuitBreakerEnabled() || dailyBudgetMicrousd <= 0) {
            return false;
        }
        long currentWindowCost = currentSuccessCostMicrousd();
        return currentWindowCost >= dailyBudgetMicrousd;
    }

    private boolean roleBudgetCircuitBreakerOpen(UserRole role) {
        long roleBudgetMicrousd = Math.max(0, properties.dailyBudgetMicrousdForRole(role));
        if (!properties.isBudgetCircuitBreakerEnabled() || roleBudgetMicrousd <= 0) {
            return false;
        }
        return currentRoleSuccessCostMicrousd(role) >= roleBudgetMicrousd;
    }

    private boolean modelBudgetCircuitBreakerOpen() {
        String modelName = configuredModelName();
        long modelBudgetMicrousd = Math.max(0, properties.getDeepseek().getDailyBudgetMicrousd());
        if (!properties.isBudgetCircuitBreakerEnabled() || modelBudgetMicrousd <= 0 || modelName.isBlank()) {
            return false;
        }
        return currentModelSuccessCostMicrousd(modelName) >= modelBudgetMicrousd;
    }

    private void auditBudgetCircuitOpen(
            Long orderId,
            BootstrapIdentity identity,
            String agentCode,
            String contextType,
            String prompt) {
        long currentWindowCost = currentSuccessCostMicrousd();
        long dailyBudgetMicrousd = Math.max(0, properties.getDailyBudgetMicrousd());
        aiGovernanceAuditTransaction.executeWithoutResult(status -> {
            audit(
                    orderId,
                    identity,
                    agentCode,
                    contextType,
                    prompt,
                    BUDGET_CIRCUIT_OPEN_STATUS,
                    new AiModelResult("ai-budget-circuit-open", BUDGET_CIRCUIT_OPEN_MODEL_NAME, 0, null));
            emitExternalAlertOutbox(
                    orderId,
                    BUDGET_CIRCUIT_OPEN_STATUS,
                    budgetCircuitOpenMessage(currentWindowCost, dailyBudgetMicrousd),
                    currentWindowCost,
                    dailyBudgetMicrousd);
        });
    }

    private void auditBudgetRoleCircuitOpen(
            Long orderId,
            BootstrapIdentity identity,
            String agentCode,
            String contextType,
            String prompt) {
        String actorRole = identity.role().name();
        long currentWindowCost = currentRoleSuccessCostMicrousd(identity.role());
        long roleBudgetMicrousd = Math.max(0, properties.dailyBudgetMicrousdForRole(identity.role()));
        aiGovernanceAuditTransaction.executeWithoutResult(status -> {
            audit(
                    orderId,
                    identity,
                    agentCode,
                    contextType,
                    prompt,
                    BUDGET_ROLE_CIRCUIT_OPEN_STATUS,
                    new AiModelResult("ai-budget-role-circuit-open", BUDGET_ROLE_CIRCUIT_OPEN_MODEL_NAME, 0, null));
            emitExternalAlertOutbox(
                    orderId,
                    BUDGET_ROLE_CIRCUIT_OPEN_STATUS,
                    budgetRoleCircuitOpenMessage(actorRole, currentWindowCost, roleBudgetMicrousd),
                    currentWindowCost,
                    roleBudgetMicrousd,
                    actorRole);
        });
    }

    private void auditBudgetModelCircuitOpen(
            Long orderId,
            BootstrapIdentity identity,
            String agentCode,
            String contextType,
            String prompt) {
        String modelName = configuredModelName();
        long currentWindowCost = currentModelSuccessCostMicrousd(modelName);
        long modelBudgetMicrousd = Math.max(0, properties.getDeepseek().getDailyBudgetMicrousd());
        aiGovernanceAuditTransaction.executeWithoutResult(status -> {
            audit(
                    orderId,
                    identity,
                    agentCode,
                    contextType,
                    prompt,
                    BUDGET_MODEL_CIRCUIT_OPEN_STATUS,
                    new AiModelResult("ai-budget-model-circuit-open", BUDGET_MODEL_CIRCUIT_OPEN_MODEL_NAME, 0, null));
            emitExternalAlertOutbox(
                    orderId,
                    BUDGET_MODEL_CIRCUIT_OPEN_STATUS,
                    budgetModelCircuitOpenMessage(modelName, currentWindowCost, modelBudgetMicrousd),
                    currentWindowCost,
                    modelBudgetMicrousd,
                    null,
                    modelName);
        });
    }

    private boolean isRetryableModelFailure(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof HttpServerErrorException || current instanceof ResourceAccessException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private AiModelResult deterministic(String content) {
        return new AiModelResult(content, DETERMINISTIC_MODEL_NAME, estimateTokenCount(content), null);
    }

    private List<AiGovernanceLocalHardeningResponse.PromptTemplate> promptVersionCatalog() {
        return List.of(
                promptTemplate("AI_TRANSLATE", "ORDER_TRANSLATION_DRAFT", "CS", true),
                promptTemplate("AI_CS_QUERY", "INTERNAL_ORDER_SUMMARY", "CS", true),
                promptTemplate("AI_DOCTOR_ORDER_QUERY", "DOCTOR_ORDER_ASSISTANT_READ_MODEL", "DOCTOR", false),
                promptTemplate("AI_CHECK_MISSING", "ORDER_FORM_REQUIRED_FIELDS", "DOCTOR/CS", false),
                promptTemplate("AI_PRODUCTION_NOTE", "PRODUCTION_NOTE_DRAFT", "CS/WORKER", true));
    }

    private AiGovernanceLocalHardeningResponse.PromptTemplate promptTemplate(
            String agentCode,
            String contextType,
            String ownerRole,
            boolean humanConfirmationRequired) {
        return new AiGovernanceLocalHardeningResponse.PromptTemplate(
                agentCode,
                promptVersionFor(agentCode),
                contextType,
                ownerRole,
                "LOCAL_CODE_VERSIONED",
                false,
                humanConfirmationRequired);
    }

    private List<AiGovernanceLocalHardeningResponse.Ai3SafetyCase> ai3SafetyMatrix() {
        return List.of(
                new AiGovernanceLocalHardeningResponse.Ai3SafetyCase(
                        AI3_DOCTOR_INTERNAL_SAFETY_MATRIX,
                        "内部工序 / 员工 / 返工 / 工时 / 绩效",
                        "SAFE_REFUSAL",
                        "DoctorOrderAssistantReadModel",
                        List.of("internal_status", "process_name", "assigned_username", "work_log", "performance", "rework")),
                new AiGovernanceLocalHardeningResponse.Ai3SafetyCase(
                        "AI3_DOCTOR_PUBLIC_STATUS_ONLY",
                        "公开进度 / 账单 / 物流 / 医生可见消息",
                        "SUCCESS_OR_SAFE_REFUSAL",
                        "DoctorOrderAssistantReadModel",
                        List.of("production_note", "check_record", "node_instance_id", "employee_name")));
    }

    private void enforceAiRateLimit(
            Long orderId,
            BootstrapIdentity identity,
            String agentCode,
            String contextType,
            String prompt) {
        if (!aiModelClient.isEnabled() || properties.getMaxRequestsPerUserHour() <= 0) {
            return;
        }
        long usedRequests = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM ai_audit_log
                        WHERE actor_user_id = :actorUserId
                          AND model_name <> :deterministicModel
                          AND result_status = 'SUCCESS'
                          AND created_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 HOUR)
                        """)
                .param("actorUserId", identity.userId())
                .param("deterministicModel", DETERMINISTIC_MODEL_NAME)
                .query(Long.class)
                .single();
        if (usedRequests < properties.getMaxRequestsPerUserHour()) {
            return;
        }
        aiGovernanceAuditTransaction.executeWithoutResult(status -> audit(
                orderId,
                identity,
                agentCode,
                contextType,
                prompt,
                RATE_LIMIT_STATUS,
                new AiModelResult("ai-rate-limited", RATE_LIMIT_MODEL_NAME, 0, null)));
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "AI request rate limit exceeded");
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    /**
     * 取出与提问最相关的 FAQ 条目。相关度用「问题标题与关键词命中的分词数」粗排——
     * 一期不引入检索引擎；命中不到时返回空列表，由调用方走「联系客服」兜底，而不是把整库塞给模型。
     */
    private List<FaqEntry> matchFaqEntries(String question, String category, BootstrapIdentity identity) {
        String normalizedCategory = category == null || category.isBlank() ? null : category.trim();
        String audience = identity.role() == UserRole.DOCTOR ? "DOCTOR" : "INTERNAL";
        List<FaqEntry> entries = jdbcClient.sql("""
                        SELECT faq_id, category, question, answer, keywords, source_note
                        FROM ai_faq_entry
                        WHERE status = 'ACTIVE'
                          AND (audience = :audience OR audience = 'DOCTOR')
                          AND (:category IS NULL OR category = :category)
                        ORDER BY sort_order ASC, faq_id ASC
                        """)
                .param("audience", audience)
                .param("category", normalizedCategory)
                .query((rs, rowNum) -> new FaqEntry(
                        rs.getLong("faq_id"),
                        rs.getString("category"),
                        rs.getString("question"),
                        rs.getString("answer"),
                        rs.getString("keywords"),
                        rs.getString("source_note")))
                .list();
        if (entries.isEmpty()) {
            return List.of();
        }
        String lowered = question.toLowerCase(Locale.ROOT);
        List<FaqEntry> scored = entries.stream()
                .filter(entry -> faqMatchScore(entry, lowered) > 0)
                .sorted((left, right) -> Integer.compare(
                        faqMatchScore(right, lowered), faqMatchScore(left, lowered)))
                .limit(FAQ_CONTEXT_LIMIT)
                .toList();
        // 指定了分类却没有关键词命中时，把该分类的条目整体作为上下文；否则视为未命中。
        if (scored.isEmpty() && normalizedCategory != null) {
            return entries.stream().limit(FAQ_CONTEXT_LIMIT).toList();
        }
        return scored;
    }

    private int faqMatchScore(FaqEntry entry, String loweredQuestion) {
        int score = 0;
        for (String keyword : nullToBlank(entry.keywords()).split(",")) {
            String trimmed = keyword.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty() && loweredQuestion.contains(trimmed)) {
                score += 2;
            }
        }
        String loweredTitle = nullToBlank(entry.question()).toLowerCase(Locale.ROOT);
        for (int index = 0; index + 2 <= loweredTitle.length(); index++) {
            if (loweredQuestion.contains(loweredTitle.substring(index, index + 2))) {
                score += 1;
            }
        }
        return score;
    }

    private List<CatalogCandidate> loadRecommendationCandidates() {
        return jdbcClient.sql("""
                        SELECT product.product_id,
                               product.config_version_id,
                               product.product_code,
                               product.display_name,
                               product.workflow_product_type,
                               product.pricing_status,
                               category.display_name AS category_name
                        FROM catalog_product_v2 product
                        JOIN catalog_category_v2 category
                          ON category.category_id = product.category_id
                        JOIN catalog_config_version version
                          ON version.config_version_id = product.config_version_id
                        WHERE product.status = 'ACTIVE'
                          AND category.status = 'ACTIVE'
                          AND version.publication_status = 'ACTIVE'
                          AND version.effective_at <= CURRENT_TIMESTAMP(3)
                        ORDER BY category.sort_order ASC, product.sort_order ASC, product.product_id ASC
                        LIMIT %d
                        """.formatted(PRODUCT_CANDIDATE_LIMIT))
                .query((rs, rowNum) -> new CatalogCandidate(
                        rs.getLong("product_id"),
                        rs.getLong("config_version_id"),
                        rs.getString("product_code"),
                        rs.getString("display_name"),
                        rs.getString("category_name"),
                        rs.getString("workflow_product_type"),
                        rs.getString("pricing_status")))
                .list();
    }

    private List<AiProductRecommendationResponse.ClinicHistoryItem> loadClinicProductHistory(Long clinicId) {
        if (clinicId == null) {
            return List.of();
        }
        return jdbcClient.sql("""
                        SELECT product_type, COUNT(*) AS order_count
                        FROM orders
                        WHERE clinic_id = :clinicId
                        GROUP BY product_type
                        ORDER BY order_count DESC, product_type ASC
                        LIMIT 10
                        """)
                .param("clinicId", clinicId)
                .query((rs, rowNum) -> new AiProductRecommendationResponse.ClinicHistoryItem(
                        rs.getString("product_type"), rs.getLong("order_count")))
                .list();
    }

    /**
     * 模型给出的产品编号必须与候选集取交集后才进入结构化结果，因此界面上的推荐卡片不可能出现目录里不存在的产品。
     * 模型没有给出可用编号时，退回到「按诊所历史下单分布排序」的服务端规则，保证结果始终可解释。
     */
    private List<AiProductRecommendationResponse.Recommendation> rankRecommendations(
            List<CatalogCandidate> candidates,
            List<AiProductRecommendationResponse.ClinicHistoryItem> history,
            List<Long> modelPickedProductIds) {
        Map<String, Long> historyByType = new LinkedHashMap<>();
        history.forEach(item -> historyByType.put(nullToBlank(item.productType()), item.orderCount()));
        Map<Long, CatalogCandidate> candidateById = new LinkedHashMap<>();
        candidates.forEach(candidate -> candidateById.put(candidate.productId(), candidate));

        List<CatalogCandidate> ordered = modelPickedProductIds.stream()
                .map(candidateById::get)
                .filter(java.util.Objects::nonNull)
                .limit(PRODUCT_RECOMMENDATION_LIMIT)
                .toList();
        boolean fromModel = !ordered.isEmpty();
        if (!fromModel) {
            ordered = candidates.stream()
                    .sorted((left, right) -> Long.compare(
                            historyByType.getOrDefault(nullToBlank(right.workflowProductType()), 0L),
                            historyByType.getOrDefault(nullToBlank(left.workflowProductType()), 0L)))
                    .limit(PRODUCT_RECOMMENDATION_LIMIT)
                    .toList();
        }

        boolean modelPicked = fromModel;
        return ordered.stream()
                .map(candidate -> {
                    long historyCount = historyByType.getOrDefault(nullToBlank(candidate.workflowProductType()), 0L);
                    String reason;
                    if (historyCount > 0) {
                        reason = "该诊所历史上以 " + candidate.workflowProductType() + " 类订单为主（" + historyCount + " 单）。";
                    } else if (modelPicked) {
                        reason = "依据本次病例描述给出的建议，理由见下方说明。";
                    } else {
                        reason = "当前生效目录中的可选产品，该诊所暂无同类历史订单。";
                    }
                    return new AiProductRecommendationResponse.Recommendation(
                            candidate.productId(),
                            candidate.productCode(),
                            candidate.displayName(),
                            candidate.categoryName(),
                            candidate.workflowProductType(),
                            candidate.pricingStatus(),
                            reason);
                })
                .toList();
    }

    private List<Long> parseRecommendedProductIds(String content, List<CatalogCandidate> candidates) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        Set<Long> allowed = candidates.stream()
                .map(CatalogCandidate::productId)
                .collect(java.util.stream.Collectors.toSet());
        List<Long> picked = new ArrayList<>();
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(RECOMMENDED_IDS_MARKER)) {
                continue;
            }
            for (String token : trimmed.substring(RECOMMENDED_IDS_MARKER.length()).split("[,，\\s]+")) {
                String digits = token.replaceAll("[^0-9]", "");
                if (digits.isEmpty()) {
                    continue;
                }
                try {
                    long productId = Long.parseLong(digits);
                    if (allowed.contains(productId) && !picked.contains(productId)) {
                        picked.add(productId);
                    }
                } catch (NumberFormatException ignored) {
                    // 模型输出不可解析时忽略该编号，退回服务端规则。
                }
            }
        }
        return picked;
    }

    private String stripRecommendedIdsLine(String content) {
        if (content == null) {
            return "";
        }
        return content.lines()
                .filter(line -> !line.trim().startsWith(RECOMMENDED_IDS_MARKER))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("")
                .trim();
    }

    private String deterministicRecommendationNote(
            List<CatalogCandidate> candidates,
            List<AiProductRecommendationResponse.ClinicHistoryItem> history) {
        String top = candidates.stream()
                .limit(PRODUCT_RECOMMENDATION_LIMIT)
                .map(CatalogCandidate::displayName)
                .reduce((left, right) -> left + "、" + right)
                .orElse("暂无可选产品");
        return "根据当前生效的产品目录"
                + (history.isEmpty() ? "" : "与该诊所历史下单分布")
                + "，可优先考虑：" + top + "。以上为建议项，请医生自行确认后选择。";
    }

    private OrderAiContext loadOrderContext(long orderId, BootstrapIdentity identity, String forbiddenMessage) {
        String dataScope = accessControlService.effectiveDataScope(identity);
        accessControlService.requireScopedIdentity(identity, dataScope);
        try {
            return jdbcClient.sql("""
                            SELECT order_id, order_no, clinic_id, doctor_user_id, product_type,
                                   form_data, internal_status, external_status, production_note
                            FROM orders
                            WHERE order_id = :orderId
                              AND (
                                  :dataScope = 'ALL'
                                  OR (:dataScope = 'CLINIC'
                                      AND (clinic_id = :clinicId OR doctor_user_id = :userId))
                                  OR (:dataScope = 'SELF'
                                      AND (
                                          doctor_user_id = :userId
                                          OR cs_user_id = :userId
                                          OR EXISTS (
                                              SELECT 1
                                              FROM order_process_instance scoped_i
                                              JOIN order_process_node scoped_n
                                                ON scoped_n.instance_id = scoped_i.instance_id
                                              WHERE scoped_i.order_id = orders.order_id
                                                AND scoped_n.assigned_user_id = :userId
                                          )
                                      ))
                              )
                            """)
                    .param("orderId", orderId)
                    .param("dataScope", dataScope)
                    .param("userId", identity.userId())
                    .param("clinicId", identity.clinicId())
                    .query((rs, rowNum) -> new OrderAiContext(
                            rs.getLong("order_id"),
                            rs.getString("order_no"),
                            rs.getLong("clinic_id"),
                            rs.getObject("doctor_user_id", Long.class),
                            rs.getString("product_type"),
                            rs.getString("form_data"),
                            rs.getString("internal_status"),
                            rs.getString("external_status"),
                            rs.getString("production_note")))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            if (orderExists(orderId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, forbiddenMessage, ex);
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found", ex);
        }
    }

    private List<String> buildCsReferenceDataNotes(OrderAiContext context) {
        List<String> notes = new ArrayList<>();
        notes.add("订单基础：orders.order_no、product_type、internal_status、external_status");
        notes.add("生产上下文：orders.internal_status 与 production_note，仅供客服内部理解，不自动写入生产备注");
        notes.add(messageReferenceNote(context.orderId()));
        notes.add(fileReferenceNote(context.orderId()));
        notes.add(attachmentPreviewReferenceNote(context.orderId()));
        notes.add(billReferenceNote(context.orderId()));
        notes.add(logisticsReferenceNote(context.orderId()));
        return notes;
    }

    private List<String> buildProductionNoteKnowledgeContextNotes(
            OrderAiContext context, Map<String, String> customerRequirements) {
        List<String> notes = new ArrayList<>();
        notes.add("默认模板：PHASE_ONE_DEFAULT_V1；客户模板未确认，不能声明为真实客户模板");
        notes.add("订单基础：orders.order_no、product_type、external_status、internal_status");
        notes.add("表单数据：orders.form_data 与订单提交快照的兼容投影，用于整理医生/客户需求和资料完整性");
        notes.add("客户档案特殊要求：" + customerRequirementSummary(customerRequirements));
        notes.add("已有生产备注：orders.production_note，仅作为内部增量上下文，不覆盖历史备注");
        notes.add(messageReferenceNote(context.orderId()));
        notes.add(fileReferenceNote(context.orderId()));
        notes.add(billReferenceNote(context.orderId()));
        notes.add(logisticsReferenceNote(context.orderId()));
        notes.add("人工确认：草稿只可由 CS / WORKER / ADMIN 确认后写入生产备注");
        return notes;
    }

    private String defaultProductionNoteDraft(
            JsonNode normalizedFormData, Map<String, String> customerRequirements) {
        JsonNode formValues = normalizedFormData.path("form_values").isObject()
                ? normalizedFormData.path("form_values")
                : normalizedFormData;
        List<String> orderLines = new ArrayList<>();
        addBusinessField(orderLines, formValues, "牙位", "tooth_position", "tooth", "teeth");
        addBusinessField(orderLines, formValues, "颜色", "shade", "color");
        addBusinessField(orderLines, formValues, "材料", "material");

        StringBuilder draft = new StringBuilder("订单制作信息");
        if (orderLines.isEmpty()) {
            draft.append("\n- 订单制作参数请按页面已提交资料核对");
        } else {
            orderLines.forEach(line -> draft.append("\n- ").append(line));
        }

        draft.append("\n\n客户档案特殊要求（初审时自动带入）");
        if (customerRequirements.isEmpty()) {
            draft.append("\n- 当前客户档案未维护特殊要求");
        } else {
            customerRequirements.forEach((label, value) -> draft.append("\n- ").append(label).append("：").append(value));
        }

        String instruction = firstBusinessValue(
                formValues, "instruction", "customer_instruction", "description", "special_requirements", "notes", "doctor_note");
        if (!instruction.isBlank()) {
            draft.append("\n\n本单客户指示\n").append(instruction);
        }
        return draft.toString();
    }

    private String confirmedProductionNoteBlock(String draftNote, String confirmationNote, BootstrapIdentity identity) {
        return draftNote;
    }

    private Map<String, String> loadCustomerProductionRequirements(long clinicId) {
        Map<String, String> storedValues = new LinkedHashMap<>();
        jdbcClient.sql("""
                        SELECT preference_key, CAST(preference_value AS CHAR) AS preference_value
                        FROM customer_preference
                        WHERE clinic_id = :clinicId
                        """)
                .param("clinicId", clinicId)
                .query((rs, rowNum) -> {
                    storedValues.put(
                            rs.getString("preference_key"),
                            preferenceDisplayValue(rs.getString("preference_value")));
                    return 1;
                })
                .list();

        Map<String, String> requirements = new LinkedHashMap<>();
        for (Map.Entry<String, String> category : CUSTOMER_REQUIREMENT_CATEGORIES) {
            String value = nullToBlank(storedValues.get(category.getKey())).trim();
            if (!value.isBlank()) {
                requirements.put(category.getValue(), value);
            }
        }
        return requirements;
    }

    private String preferenceDisplayValue(String storedJson) {
        if (storedJson == null || storedJson.isBlank()) {
            return "";
        }
        try {
            return businessJsonValue(objectMapper.readTree(storedJson));
        } catch (JsonProcessingException ex) {
            return "";
        }
    }

    private String businessJsonValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isTextual()) {
            return value.asText().trim();
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        if (value.isArray()) {
            List<String> items = new ArrayList<>();
            value.forEach(item -> {
                String display = businessJsonValue(item);
                if (!display.isBlank()) {
                    items.add(display);
                }
            });
            return String.join("、", items);
        }
        if (value.isObject()) {
            List<String> items = new ArrayList<>();
            value.fields().forEachRemaining(entry -> {
                String display = businessJsonValue(entry.getValue());
                if (!display.isBlank()) {
                    items.add(entry.getKey() + "：" + display);
                }
            });
            return String.join("；", items);
        }
        return "";
    }

    private String customerRequirementSummary(Map<String, String> customerRequirements) {
        if (customerRequirements.isEmpty()) {
            return "当前客户档案未维护特殊要求";
        }
        return customerRequirements.entrySet().stream()
                .map(entry -> entry.getKey() + "：" + entry.getValue())
                .reduce((left, right) -> left + "；" + right)
                .orElse("当前客户档案未维护特殊要求");
    }

    private void addBusinessField(List<String> lines, JsonNode formValues, String label, String... keys) {
        String value = firstBusinessValue(formValues, keys);
        if (!value.isBlank()) {
            lines.add(label + "：" + value);
        }
    }

    private String firstBusinessValue(JsonNode formValues, String... keys) {
        if (formValues == null || !formValues.isObject()) {
            return "";
        }
        for (String key : keys) {
            String display = businessJsonValue(formValues.get(key));
            if (!display.isBlank()) {
                return display;
            }
        }
        return "";
    }

    private String messageReferenceNote(long orderId) {
        Long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_message
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        if (count == null || count == 0) {
            return "沟通消息：order_message 未找到当前订单消息";
        }
        List<String> samples = jdbcClient.sql("""
                        SELECT sender_role, visibility, review_status
                        FROM order_message
                        WHERE order_id = :orderId
                        ORDER BY created_at DESC, message_id DESC
                        LIMIT 3
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> rs.getString("sender_role")
                        + "/"
                        + rs.getString("visibility")
                        + "/"
                        + rs.getString("review_status"))
                .list();
        return "沟通消息：order_message 共 " + count + " 条，最近状态 " + String.join("、", samples);
    }

    private String fileReferenceNote(long orderId) {
        Long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM orders o
                        JOIN file_resource f
                          ON f.order_id = o.order_id
                          OR (
                              f.attachment_scope = 'SHARED'
                              AND f.case_group_id = o.group_id
                          )
                        WHERE o.order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        if (count == null || count == 0) {
            return "附件：file_resource 未找到当前订单附件";
        }
        List<String> samples = jdbcClient.sql("""
                        SELECT f.source_type, f.visibility, f.status
                        FROM orders o
                        JOIN file_resource f
                          ON f.order_id = o.order_id
                          OR (
                              f.attachment_scope = 'SHARED'
                              AND f.case_group_id = o.group_id
                          )
                        WHERE o.order_id = :orderId
                        ORDER BY f.created_at DESC, f.file_id DESC
                        LIMIT 3
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> rs.getString("source_type")
                        + "/"
                        + rs.getString("visibility")
                        + "/"
                        + rs.getString("status"))
                .list();
        return "附件：file_resource 共 " + count + " 个，最近类型 " + String.join("、", samples);
    }

    private String attachmentPreviewReferenceNote(long orderId) {
        Long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM file_resource
                        WHERE order_id = :orderId
                          AND source_type = 'MESSAGE_ATTACHMENT'
                          AND status = 'ACTIVE'
                          AND upload_status = 'COMPLETED'
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        if (count == null || count == 0) {
            return "消息附件预览：未找到可预览的已完成附件";
        }
        return "消息附件预览：file_resource 已聚合 " + Math.min(count, 5)
                + " 个短时效预览上下文，仅供客服人工复核";
    }

    private List<CsAttachmentContext> buildCsAttachmentContexts(long orderId, BootstrapIdentity identity) {
        List<FileContextRow> files = jdbcClient.sql("""
                        SELECT file_id, source_type, visibility, original_filename, content_type, file_size
                        FROM file_resource
                        WHERE order_id = :orderId
                          AND source_type = 'MESSAGE_ATTACHMENT'
                          AND status = 'ACTIVE'
                          AND upload_status = 'COMPLETED'
                        ORDER BY created_at DESC, file_id DESC
                        LIMIT 5
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new FileContextRow(
                        rs.getLong("file_id"),
                        rs.getString("source_type"),
                        rs.getString("visibility"),
                        rs.getString("original_filename"),
                        rs.getString("content_type"),
                        rs.getObject("file_size", Long.class)))
                .list();
        List<CsAttachmentContext> contexts = new ArrayList<>();
        for (FileContextRow file : files) {
            FileSignedUrlResponse signedUrl = fileResourceService.createPreviewUrl(file.fileId(), identity);
            contexts.add(new CsAttachmentContext(
                    file.fileId(),
                    file.sourceType(),
                    file.visibility(),
                    file.originalFilename(),
                    file.contentType(),
                    file.fileSize(),
                    signedUrl.previewUrl(),
                    signedUrl.expiresInSeconds(),
                    "AI-2 附件预览上下文，仅供客服人工复核，不会自动发送给医生或写入订单。"));
        }
        return contexts;
    }

    private String attachmentContextText(List<CsAttachmentContext> attachmentContexts) {
        return attachmentContexts.stream()
                .map(context -> "- file_id="
                        + context.fileId()
                        + "，source_type="
                        + context.sourceType()
                        + "，filename="
                        + context.originalFilename()
                        + "，content_type="
                        + nullToBlank(context.contentType()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String billReferenceNote(long orderId) {
        return jdbcClient.sql("""
                        SELECT bill_status, payment_status, amount_cent, currency
                        FROM order_bill
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> {
                    Long amountCent = rs.getObject("amount_cent", Long.class);
                    String amountText = amountCent == null ? "" : amountCent.toString();
                    return "账单：order_bill 状态 "
                            + rs.getString("bill_status")
                            + "，付款状态 "
                            + rs.getString("payment_status")
                            + "，金额 "
                            + amountText
                            + " "
                            + nullToBlank(rs.getString("currency"));
                })
                .optional()
                .orElse("账单：order_bill 未找到当前订单账单");
    }

    private String logisticsReferenceNote(long orderId) {
        return jdbcClient.sql("""
                        SELECT carrier_name, tracking_no, logistics_status
                        FROM order_logistics
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> "物流：order_logistics 状态 "
                        + rs.getString("logistics_status")
                        + "，承运商 "
                        + nullToBlank(rs.getString("carrier_name"))
                        + "，单号 "
                        + nullToBlank(rs.getString("tracking_no")))
                .optional()
                .orElse("物流：order_logistics 未找到当前订单物流");
    }

    private boolean orderExists(long orderId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM orders WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single() > 0;
    }

    private List<RequiredField> requiredFields(long orderId, String productType) {
        CatalogFormSchemaSnapshot snapshot = jdbcClient.sql("""
                        SELECT form_schema_snapshot
                        FROM order_catalog_snapshot
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new CatalogFormSchemaSnapshot(rs.getString("form_schema_snapshot")))
                .optional()
                .orElse(null);
        if (snapshot != null && snapshot.json() != null) {
            JsonNode rules = readFormData(snapshot.json());
            List<RequiredField> fields = new ArrayList<>();
            if (rules.isArray()) {
                for (JsonNode rule : rules) {
                    if (!"FORM_SCHEMA".equals(rule.path("rule_type").asText())) {
                        continue;
                    }
                    JsonNode schemaFields = rule.path("schema").path("fields");
                    if (!schemaFields.isArray()) {
                        continue;
                    }
                    for (JsonNode field : schemaFields) {
                        String key = field.path("key").asText("").trim();
                        if (field.path("required").asBoolean(false) && !key.isBlank()) {
                            String label = field.path("label").asText(key).trim();
                            JsonNode visibleWhen = field.path("visible_when").isObject()
                                    ? field.path("visible_when").deepCopy()
                                    : null;
                            fields.add(new RequiredField(key, label.isBlank() ? key : label, visibleWhen));
                        }
                    }
                }
            }
            return fields;
        }
        return jdbcClient.sql("""
                        SELECT field_key, field_label
                        FROM form_field_config
                        WHERE product_type = :productType
                          AND required_flag = 1
                          AND status = 'ACTIVE'
                        ORDER BY sort_order, field_id
                        """)
                .param("productType", productType)
                .query((rs, rowNum) -> new RequiredField(
                        rs.getString("field_key"),
                        rs.getString("field_label"),
                        null))
                .list();
    }

    private record CatalogFormSchemaSnapshot(String json) {
    }

    private JsonNode readFormData(String formData) {
        if (formData == null || formData.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(formData);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "invalid stored order json", ex);
        }
    }

    private boolean isMissing(JsonNode node) {
        if (node == null || node.isNull()) {
            return true;
        }
        if (node.isTextual()) {
            return node.asText().isBlank();
        }
        if (node.isArray() || node.isObject()) {
            return node.isEmpty();
        }
        return false;
    }

    private boolean asksForInternalData(String question) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        return DOCTOR_INTERNAL_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    private boolean outputGuardTriggered(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String normalized = content.toLowerCase(Locale.ROOT);
        return OUTPUT_GUARD_PATTERNS.stream().anyMatch(normalized::contains);
    }

    private String publicSuffix(DoctorOrderAssistantReadModel readModel) {
        List<String> parts = new ArrayList<>();
        if (readModel.publicMessage() != null && !readModel.publicMessage().isBlank()) {
            parts.add(readModel.publicMessage());
        }
        if (readModel.visibleMessageSummary() != null && !readModel.visibleMessageSummary().isBlank()) {
            parts.add(readModel.visibleMessageSummary());
        }
        if (readModel.billStatus() != null && !readModel.billStatus().isBlank()) {
            parts.add("账单状态：" + readModel.billStatus());
        }
        if (readModel.logisticsStatus() != null && !readModel.logisticsStatus().isBlank()) {
            parts.add("物流状态：" + readModel.logisticsStatus());
        }
        if (readModel.trackingNo() != null && !readModel.trackingNo().isBlank()) {
            parts.add("物流单号：" + readModel.trackingNo());
        }
        return parts.isEmpty() ? "" : "。" + String.join("。", parts);
    }

    private void audit(
            Long orderId,
            BootstrapIdentity identity,
            String agentCode,
            String contextType,
            String prompt,
            String resultStatus,
            AiModelResult modelResult) {
        long estimatedCostMicrousd = estimatedCostMicrousd(modelResult);
        jdbcClient.sql("""
                        INSERT INTO ai_audit_log
                            (order_id, actor_user_id, actor_role, agent_code, request_context_type,
                             prompt_version, prompt_hash, model_name, input_token_count, output_token_count,
                             estimated_cost_microusd, result_status)
                        VALUES
                            (:orderId, :actorUserId, :actorRole, :agentCode, :contextType,
                             :promptVersion, :promptHash, :modelName, :inputTokenCount, :outputTokenCount,
                             :estimatedCostMicrousd, :resultStatus)
                        """)
                .param("orderId", orderId)
                .param("actorUserId", identity.userId())
                .param("actorRole", identity.role().name())
                .param("agentCode", agentCode)
                .param("contextType", contextType)
                .param("promptVersion", promptVersionFor(agentCode))
                .param("promptHash", sha256(prompt))
                .param("modelName", modelResult.modelName())
                .param("inputTokenCount", modelResult.inputTokenCount())
                .param("outputTokenCount", modelResult.outputTokenCount())
                .param("estimatedCostMicrousd", estimatedCostMicrousd)
                .param("resultStatus", resultStatus)
                .update();
        auditBudgetExceededIfCrossed(orderId, identity, agentCode, contextType, prompt, resultStatus, modelResult,
                estimatedCostMicrousd);
    }

    private String promptVersionFor(String agentCode) {
        return switch (agentCode) {
            case "AI_TRANSLATE" -> "AI_TRANSLATE_V1";
            case "AI_CS_QUERY" -> "AI_CS_QUERY_V1";
            case "AI_DOCTOR_ORDER_QUERY" -> "AI_DOCTOR_ORDER_QUERY_V1";
            case "AI_CHECK_MISSING" -> "AI_CHECK_MISSING_V1";
            case "AI_PRODUCTION_NOTE" -> "AI_PRODUCTION_NOTE_V1";
            case "AI_FAQ" -> "AI_FAQ_V1";
            case "AI_PRODUCT_RECOMMENDATION" -> "AI_PRODUCT_RECOMMENDATION_V1";
            default -> agentCode + "_V1";
        };
    }

    private void auditBudgetExceededIfCrossed(
            Long orderId,
            BootstrapIdentity identity,
            String agentCode,
            String contextType,
            String prompt,
            String resultStatus,
            AiModelResult modelResult,
            long estimatedCostMicrousd) {
        long dailyBudgetMicrousd = Math.max(0, properties.getDailyBudgetMicrousd());
        if (dailyBudgetMicrousd <= 0
                || estimatedCostMicrousd <= 0
                || !"SUCCESS".equals(resultStatus)
                || DETERMINISTIC_MODEL_NAME.equals(modelResult.modelName())) {
            return;
        }
        long currentWindowCost = currentSuccessCostMicrousd();
        long previousWindowCost = Math.max(0, currentWindowCost - estimatedCostMicrousd);
        if (previousWindowCost >= dailyBudgetMicrousd || currentWindowCost < dailyBudgetMicrousd) {
            return;
        }
        audit(
                orderId,
                identity,
                agentCode,
                contextType,
                prompt,
                BUDGET_EXCEEDED_STATUS,
                new AiModelResult("ai-budget-exceeded", BUDGET_EXCEEDED_MODEL_NAME, 0, null));
        emitExternalAlertOutbox(
                orderId,
                BUDGET_EXCEEDED_STATUS,
                budgetExceededMessage(currentWindowCost, dailyBudgetMicrousd),
                currentWindowCost,
                dailyBudgetMicrousd);
        if (properties.isBudgetNotificationEnabled()) {
            emitBudgetExceededNotification(orderId, currentWindowCost, dailyBudgetMicrousd);
        }
    }

    private void emitBudgetExceededNotification(Long orderId, long currentWindowCost, long dailyBudgetMicrousd) {
        String orderNo = loadOrderNo(orderId);
        String message = budgetExceededMessage(currentWindowCost, dailyBudgetMicrousd);
        String payload = budgetNotificationPayload(orderId, orderNo, message, currentWindowCost, dailyBudgetMicrousd);
        jdbcClient.sql("""
                        INSERT INTO notification_event
                            (order_id, event_type, audience_role, payload, delivery_status)
                        VALUES
                            (:orderId, :eventType, 'INTERNAL', CAST(:payload AS JSON), 'PENDING')
                        """)
                .param("orderId", orderId)
                .param("eventType", BUDGET_EXCEEDED_STATUS)
                .param("payload", payload)
                .update();
        long eventId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        List<Long> userIds = jdbcClient.sql("""
                        SELECT DISTINCT u.user_id
                        FROM system_user u
                        JOIN system_user_role ur ON ur.user_id = u.user_id
                        JOIN system_role r ON r.role_id = ur.role_id
                        WHERE u.status = 'ACTIVE'
                          AND r.status = 'ACTIVE'
                          AND r.role_code IN ('ADMIN', 'CS')
                        """)
                .query(Long.class)
                .list();
        for (Long userId : userIds) {
            jdbcClient.sql("""
                            INSERT IGNORE INTO user_notification (event_id, user_id)
                            VALUES (:eventId, :userId)
                            """)
                    .param("eventId", eventId)
                    .param("userId", userId)
                    .update();
            notificationPushService.pushToUser(userId, eventId, payload);
        }
    }

    private void emitExternalAlertOutbox(
            Long orderId,
            String alertType,
            String message,
            long currentWindowCost,
            long dailyBudgetMicrousd) {
        emitExternalAlertOutbox(orderId, alertType, message, currentWindowCost, dailyBudgetMicrousd, null);
    }

    private void emitExternalAlertOutbox(
            Long orderId,
            String alertType,
            String message,
            long currentWindowCost,
            long dailyBudgetMicrousd,
            String actorRole) {
        emitExternalAlertOutbox(orderId, alertType, message, currentWindowCost, dailyBudgetMicrousd, actorRole, null);
    }

    private void emitExternalAlertOutbox(
            Long orderId,
            String alertType,
            String message,
            long currentWindowCost,
            long dailyBudgetMicrousd,
            String actorRole,
            String modelName) {
        String orderNo = loadOrderNo(orderId);
        String payload = externalAlertPayload(orderId, orderNo, alertType, actorRole, modelName, message, currentWindowCost,
                dailyBudgetMicrousd);
        jdbcClient.sql("""
                        INSERT INTO ai_external_alert_outbox
                            (order_id, alert_type, channel, payload, send_status, attempts)
                        VALUES
                            (:orderId, :alertType, :channel, CAST(:payload AS JSON), :sendStatus, 0)
                        """)
                .param("orderId", orderId)
                .param("alertType", alertType)
                .param("channel", EXTERNAL_ALERT_CHANNEL)
                .param("payload", payload)
                .param("sendStatus", EXTERNAL_ALERT_PENDING_STATUS)
                .update();
    }

    private long currentSuccessCostMicrousd() {
        return jdbcClient.sql("""
                        SELECT COALESCE(SUM(estimated_cost_microusd), 0)
                        FROM ai_audit_log
                        WHERE result_status = 'SUCCESS'
                          AND created_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 24 HOUR)
                        """)
                .query(Long.class)
                .single();
    }

    private long currentRoleSuccessCostMicrousd(UserRole role) {
        if (role == null) {
            return 0;
        }
        return jdbcClient.sql("""
                        SELECT COALESCE(SUM(estimated_cost_microusd), 0)
                        FROM ai_audit_log
                        WHERE actor_role = :actorRole
                          AND result_status = 'SUCCESS'
                          AND created_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 24 HOUR)
                        """)
                .param("actorRole", role.name())
                .query(Long.class)
                .single();
    }

    private long currentModelSuccessCostMicrousd(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return 0;
        }
        return jdbcClient.sql("""
                        SELECT COALESCE(SUM(estimated_cost_microusd), 0)
                        FROM ai_audit_log
                        WHERE model_name = :modelName
                          AND result_status = 'SUCCESS'
                          AND created_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 24 HOUR)
                        """)
                .param("modelName", modelName)
                .query(Long.class)
                .single();
    }

    private String loadOrderNo(Long orderId) {
        // AI-6 / AI-7 这类不依附具体订单的智能体，审计与告警链路允许 orderId 为空。
        if (orderId == null) {
            return null;
        }
        return jdbcClient.sql("SELECT order_no FROM orders WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single();
    }

    private String budgetExceededMessage(long currentWindowCost, long dailyBudgetMicrousd) {
        return "AI 预算已达到阈值：近 24 小时估算成本 "
                + currentWindowCost
                + " microUSD，阈值 "
                + dailyBudgetMicrousd
                + " microUSD。";
    }

    private String budgetCircuitOpenMessage(long currentWindowCost, long dailyBudgetMicrousd) {
        return "AI 预算熔断已命中：近 24 小时估算成本 "
                + currentWindowCost
                + " microUSD，阈值 "
                + dailyBudgetMicrousd
                + " microUSD。";
    }

    private String budgetRoleCircuitOpenMessage(String actorRole, long currentWindowCost, long roleBudgetMicrousd) {
        return "AI 角色预算熔断已命中：角色 "
                + actorRole
                + " 近 24 小时估算成本 "
                + currentWindowCost
                + " microUSD，角色阈值 "
                + roleBudgetMicrousd
                + " microUSD。";
    }

    private String budgetModelCircuitOpenMessage(String modelName, long currentWindowCost, long modelBudgetMicrousd) {
        return "AI 模型预算熔断已命中：模型 "
                + modelName
                + " 近 24 小时估算成本 "
                + currentWindowCost
                + " microUSD，模型阈值 "
                + modelBudgetMicrousd
                + " microUSD。";
    }

    private String budgetNotificationPayload(
            Long orderId,
            String orderNo,
            String message,
            long currentWindowCost,
            long dailyBudgetMicrousd) {
        try {
            return objectMapper.writeValueAsString(new AiBudgetNotificationPayload(
                    BUDGET_EXCEEDED_STATUS,
                    orderId,
                    orderNo,
                    message,
                    currentWindowCost,
                    dailyBudgetMicrousd));
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "invalid AI budget payload", ex);
        }
    }

    private String externalAlertPayload(
            Long orderId,
            String orderNo,
            String event,
            String role,
            String model,
            String message,
            long currentWindowCost,
            long dailyBudgetMicrousd) {
        try {
            return objectMapper.writeValueAsString(new AiExternalAlertPayload(
                    event,
                    role,
                    model,
                    orderId,
                    orderNo,
                    message,
                    currentWindowCost,
                    dailyBudgetMicrousd));
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "invalid AI external alert payload", ex);
        }
    }

    private String configuredModelName() {
        String modelName = properties.getDeepseek().getModel();
        return modelName == null ? "" : modelName.trim();
    }

    private long estimatedCostMicrousd(AiModelResult modelResult) {
        long inputCost = Math.max(0, properties.getInputTokenCostMicrousd());
        long outputCost = Math.max(0, properties.getOutputTokenCostMicrousd());
        long outputTokens = modelResult.outputTokenCount() == null ? 0 : modelResult.outputTokenCount();
        return modelResult.inputTokenCount() * inputCost + outputTokens * outputCost;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private int estimateTokenCount(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return Math.max(1, value.trim().length() / 2);
    }

    public record CsQueryResult(
            String answer,
            List<String> referenceDataNotes,
            List<CsAttachmentContext> attachmentContexts) {
    }

    public record CsAttachmentContext(
            @JsonProperty("file_id") long fileId,
            @JsonProperty("source_type") String sourceType,
            String visibility,
            @JsonProperty("original_filename") String originalFilename,
            @JsonProperty("content_type") String contentType,
            @JsonProperty("file_size") Long fileSize,
            @JsonProperty("preview_url") String previewUrl,
            @JsonProperty("expires_in_seconds") int expiresInSeconds,
            @JsonProperty("review_note") String reviewNote) {
    }

    public record ProductionNoteDraftResult(
            String draftNote,
            String templateVersion,
            List<String> knowledgeContextNotes,
            boolean requiresCustomerTemplateConfirmation) {
    }

    public record ProductionNoteConfirmationResult(
            String productionNote,
            String templateVersion,
            boolean requiresCustomerTemplateConfirmation) {
    }

    private record FaqEntry(
            long faqId,
            String category,
            String question,
            String answer,
            String keywords,
            String sourceNote) {
    }

    private record CatalogCandidate(
            long productId,
            long configVersionId,
            String productCode,
            String displayName,
            String categoryName,
            String workflowProductType,
            String pricingStatus) {
    }

    private record OrderAiContext(
            long orderId,
            String orderNo,
            long clinicId,
            Long doctorUserId,
            String productType,
            String formData,
            String internalStatus,
            String externalStatus,
            String productionNote) {
    }

    private record RequiredField(String fieldKey, String fieldLabel, JsonNode visibleWhen) {

        private boolean visible(JsonNode values) {
            if (visibleWhen == null || !visibleWhen.isObject()) {
                return true;
            }
            String controllingField = visibleWhen.path("field").asText("");
            JsonNode expected = visibleWhen.get("equals");
            return controllingField.isBlank()
                    || expected == null
                    || expected.equals(values.get(controllingField));
        }
    }

    private record FileContextRow(
            long fileId,
            String sourceType,
            String visibility,
            String originalFilename,
            String contentType,
            Long fileSize) {
    }

    private record AiBudgetNotificationPayload(
            String event,
            Long orderId,
            String orderNo,
            String message,
            long estimatedCostMicrousd,
            long dailyBudgetMicrousd) {
    }

    private record AiExternalAlertPayload(
            String event,
            String role,
            String model,
            Long orderId,
            String orderNo,
            String message,
            long estimatedCostMicrousd,
            long dailyBudgetMicrousd) {
    }
}
