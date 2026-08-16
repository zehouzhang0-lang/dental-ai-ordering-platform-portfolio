package com.yuri.aiorder.order.casegroup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuri.aiorder.catalog.ActiveCatalogProductReader;
import com.yuri.aiorder.catalog.ActiveCatalogProductReader.ActiveProduct;
import com.yuri.aiorder.catalog.ActiveCatalogProductReader.BindingRow;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.BusinessTime;
import com.yuri.aiorder.common.auth.AccessControlService;
import com.yuri.aiorder.order.casegroup.CaseGroupItemModels.BindSharedFilesRequest;
import com.yuri.aiorder.order.casegroup.CaseGroupItemModels.CopyGroupItemRequest;
import com.yuri.aiorder.order.casegroup.CaseGroupItemModels.CreateGroupItemRequest;
import com.yuri.aiorder.order.casegroup.CaseGroupItemModels.DeleteGroupItemRequest;
import com.yuri.aiorder.order.casegroup.CaseGroupItemModels.QuantitySelection;
import com.yuri.aiorder.order.casegroup.CaseGroupItemModels.SubmitCaseGroupRequest;
import com.yuri.aiorder.order.casegroup.CaseGroupItemModels.UpdateGroupItemRequest;
import com.yuri.aiorder.order.rules.OrderRuleSelections;
import com.yuri.aiorder.order.rules.OrderRuleService;
import com.yuri.aiorder.order.status.InternalOrderStatus;
import com.yuri.aiorder.order.status.OrderStatusService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CaseGroupDraftService {

    private static final long MAX_FILE_SIZE_BYTES = 500L * 1024 * 1024;
    private static final Set<String> DOCTOR_FILE_VISIBILITY = Set.of("DOCTOR", "DOCTOR_CS", "ALL");

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;
    private final OrderStatusService orderStatusService;
    private final CaseGroupService caseGroupService;
    private final ActiveCatalogProductReader catalogReader;
    private final OrderRuleService orderRuleService;

    public CaseGroupDraftService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            AccessControlService accessControlService,
            OrderStatusService orderStatusService,
            CaseGroupService caseGroupService,
            ActiveCatalogProductReader catalogReader,
            OrderRuleService orderRuleService) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
        this.orderStatusService = orderStatusService;
        this.caseGroupService = caseGroupService;
        this.catalogReader = catalogReader;
        this.orderRuleService = orderRuleService;
    }

    @Transactional
    public CaseGroupResponse addItem(
            long groupId, CreateGroupItemRequest request, BootstrapIdentity identity) {
        LockedGroup group = lockOwnedDraft(groupId, identity);
        String clientKey = request.itemClientKey().trim();
        DraftOrder existing = findItemByClientKey(groupId, clientKey);
        if (existing != null) {
            if (!Objects.equals(existing.productId(), request.productId())
                    || !Objects.equals(existing.variantId(), request.variantId())) {
                throw conflict("item_client_key is already used by a different product");
            }
            return caseGroupService.get(groupId, identity);
        }
        requireDraftVersion(group, request.expectedDraftVersion());
        ActiveProduct product = loadActiveProduct(request.productId(), request.variantId());
        SelectionValidation selection = validateSelections(
                product,
                request.materialSelections(),
                request.accessorySelections(),
                false);
        requireObject(request.formValues(), "form_values");
        validateFormSchema(product, request.formValues(), false);
        // 订单类型 / 订单周期 / 运输类型在草稿阶段就校验：认识的值才收，未知值直接 400。
        // 回寄运单号留到提交时才强制——草稿允许先选类型后补运单号。
        OrderRuleSelections.parse(request.formValues(), false);

        int lineNo = nextLineNo(groupId);
        String orderNo = nextOrderNo();
        String formData = buildFormData(
                request.formValues(), selection.materialSelections(), selection.accessorySelections());
        try {
            jdbcClient.sql("""
                            INSERT INTO orders
                                (group_id, line_no, relationship_type, item_client_key,
                                 order_no, clinic_id, doctor_user_id, patient_id,
                                 product_type, product_id, variant_id, form_data,
                                 quoted_price_cents, quoted_price_currency, pricing_source,
                                 internal_status, external_status)
                            VALUES
                                (:groupId, :lineNo, :relationshipType, :clientKey,
                                 :orderNo, :clinicId, :doctorUserId, :patientId,
                                 :productType, :productId, :variantId, CAST(:formData AS JSON),
                                 :priceCents, :currency, :pricingSource,
                                 'DRAFT', 'DRAFT')
                            """)
                    .param("groupId", groupId)
                    .param("lineNo", lineNo)
                    .param("relationshipType", relationship(request.relationshipType(), lineNo))
                    .param("clientKey", clientKey)
                    .param("orderNo", orderNo)
                    .param("clinicId", group.clinicId())
                    .param("doctorUserId", group.doctorUserId())
                    .param("patientId", group.patientId())
                    .param("productType", product.workflowProductType())
                    .param("productId", product.productId())
                    .param("variantId", product.variantId())
                    .param("formData", formData)
                    .param("priceCents", selection.price().priceCents())
                    .param("currency", selection.price().currency())
                    .param("pricingSource", selection.price().pricingSource())
                    .update();
        } catch (DuplicateKeyException ex) {
            DraftOrder concurrent = findItemByClientKey(groupId, clientKey);
            if (concurrent == null) {
                throw ex;
            }
            if (!Objects.equals(concurrent.productId(), request.productId())
                    || !Objects.equals(concurrent.variantId(), request.variantId())) {
                throw conflict("item_client_key is already used by a different product");
            }
            return caseGroupService.get(groupId, identity);
        }
        long orderId = orderId(orderNo);
        if (request.fileIds() != null) {
            synchronizeOrderFiles(group, orderId, request.fileIds(), identity);
        }
        incrementDraftVersion(groupId, group.draftVersion());
        audit(groupId, orderId, "ADD_ITEM", null, orderSnapshot(orderId), identity.userId(), clientKey, null);
        return caseGroupService.get(groupId, identity);
    }

    @Transactional
    public CaseGroupResponse updateItem(
            long groupId,
            long orderId,
            UpdateGroupItemRequest request,
            BootstrapIdentity identity) {
        LockedGroup group = lockOwnedDraft(groupId, identity);
        requireDraftVersion(group, request.expectedDraftVersion());
        DraftOrder order = loadDraftOrder(groupId, orderId);
        ActiveProduct product = loadActiveProduct(request.productId(), request.variantId());
        SelectionValidation selection = validateSelections(
                product,
                request.materialSelections(),
                request.accessorySelections(),
                false);
        requireObject(request.formValues(), "form_values");
        validateFormSchema(product, request.formValues(), false);
        // 订单类型 / 订单周期 / 运输类型在草稿阶段就校验：认识的值才收，未知值直接 400。
        // 回寄运单号留到提交时才强制——草稿允许先选类型后补运单号。
        OrderRuleSelections.parse(request.formValues(), false);
        Map<String, Object> before = orderSnapshot(orderId);
        jdbcClient.sql("""
                        UPDATE orders
                        SET relationship_type = :relationshipType,
                            product_type = :productType,
                            product_id = :productId,
                            variant_id = :variantId,
                            form_data = CAST(:formData AS JSON),
                            quoted_price_cents = :priceCents,
                            quoted_price_currency = :currency,
                            pricing_source = :pricingSource
                        WHERE order_id = :orderId
                          AND group_id = :groupId
                          AND internal_status = 'DRAFT'
                        """)
                .param("relationshipType", relationship(request.relationshipType(), order.lineNo()))
                .param("productType", product.workflowProductType())
                .param("productId", product.productId())
                .param("variantId", product.variantId())
                .param("formData", buildFormData(
                        request.formValues(),
                        selection.materialSelections(),
                        selection.accessorySelections()))
                .param("priceCents", selection.price().priceCents())
                .param("currency", selection.price().currency())
                .param("pricingSource", selection.price().pricingSource())
                .param("orderId", orderId)
                .param("groupId", groupId)
                .update();
        if (request.fileIds() != null) {
            synchronizeOrderFiles(group, orderId, request.fileIds(), identity);
        }
        incrementDraftVersion(groupId, group.draftVersion());
        audit(groupId, orderId, "UPDATE_ITEM", before, orderSnapshot(orderId), identity.userId(), null, null);
        return caseGroupService.get(groupId, identity);
    }

    @Transactional
    public CaseGroupResponse copyItem(
            long groupId,
            long orderId,
            CopyGroupItemRequest request,
            BootstrapIdentity identity) {
        LockedGroup group = lockOwnedDraft(groupId, identity);
        requireDraftVersion(group, request.expectedDraftVersion());
        DraftOrder source = loadDraftOrder(groupId, orderId);
        String clientKey = request.itemClientKey().trim();
        DraftOrder existing = findItemByClientKey(groupId, clientKey);
        if (existing != null) {
            return caseGroupService.get(groupId, identity);
        }
        String orderNo = nextOrderNo();
        int lineNo = nextLineNo(groupId);
        try {
            jdbcClient.sql("""
                            INSERT INTO orders
                                (group_id, line_no, relationship_type, item_client_key,
                                 order_no, clinic_id, doctor_user_id, patient_id,
                                 product_type, product_id, variant_id, form_data,
                                 quoted_price_cents, quoted_price_currency, pricing_source,
                                 internal_status, external_status)
                            SELECT
                                group_id, :lineNo, 'RELATED', :clientKey,
                                :orderNo, clinic_id, doctor_user_id, patient_id,
                                product_type, product_id, variant_id, form_data,
                                quoted_price_cents, quoted_price_currency, pricing_source,
                                'DRAFT', 'DRAFT'
                            FROM orders
                            WHERE order_id = :sourceOrderId
                              AND group_id = :groupId
                              AND internal_status = 'DRAFT'
                            """)
                    .param("lineNo", lineNo)
                    .param("clientKey", clientKey)
                    .param("orderNo", orderNo)
                    .param("sourceOrderId", orderId)
                    .param("groupId", groupId)
                    .update();
        } catch (DuplicateKeyException ex) {
            if (findItemByClientKey(groupId, clientKey) == null) {
                throw ex;
            }
            return caseGroupService.get(groupId, identity);
        }
        long copiedOrderId = orderId(orderNo);
        incrementDraftVersion(groupId, group.draftVersion());
        audit(
                groupId,
                copiedOrderId,
                "COPY_ITEM",
                orderSnapshot(source.orderId()),
                orderSnapshot(copiedOrderId),
                identity.userId(),
                clientKey,
                "product-specific files are not copied; group shared files remain available");
        return caseGroupService.get(groupId, identity);
    }

    @Transactional
    public CaseGroupResponse deleteItem(
            long groupId,
            long orderId,
            DeleteGroupItemRequest request,
            BootstrapIdentity identity) {
        LockedGroup group = lockOwnedDraft(groupId, identity);
        requireDraftVersion(group, request.expectedDraftVersion());
        loadDraftOrder(groupId, orderId);
        Map<String, Object> before = orderSnapshot(orderId);
        jdbcClient.sql("""
                        UPDATE file_resource
                        SET status = 'DELETED',
                            order_id = NULL
                        WHERE order_id = :orderId
                          AND owner_user_id = :userId
                          AND status = 'ACTIVE'
                        """)
                .param("orderId", orderId)
                .param("userId", identity.userId())
                .update();
        audit(groupId, orderId, "DELETE_ITEM", before, null, identity.userId(), null, null);
        jdbcClient.sql("""
                        DELETE FROM orders
                        WHERE order_id = :orderId
                          AND group_id = :groupId
                          AND internal_status = 'DRAFT'
                        """)
                .param("orderId", orderId)
                .param("groupId", groupId)
                .update();
        incrementDraftVersion(groupId, group.draftVersion());
        return caseGroupService.get(groupId, identity);
    }

    @Transactional
    public CaseGroupResponse bindSharedFiles(
            long groupId, BindSharedFilesRequest request, BootstrapIdentity identity) {
        LockedGroup group = lockOwnedDraft(groupId, identity);
        requireDraftVersion(group, request.expectedDraftVersion());
        List<Long> selected = distinctIds(request.fileIds());
        for (Long fileId : selected) {
            validateFile(fileId, group, null, identity);
        }
        List<Long> removed;
        if (selected.isEmpty()) {
            removed = jdbcClient.sql("""
                            SELECT file_id FROM file_resource
                            WHERE case_group_id = :groupId
                              AND attachment_scope = 'SHARED'
                              AND owner_user_id = :userId
                              AND status = 'ACTIVE'
                            """)
                    .param("groupId", groupId)
                    .param("userId", identity.userId())
                    .query(Long.class)
                    .list();
        } else {
            removed = jdbcClient.sql("""
                            SELECT file_id FROM file_resource
                            WHERE case_group_id = :groupId
                              AND attachment_scope = 'SHARED'
                              AND owner_user_id = :userId
                              AND status = 'ACTIVE'
                              AND file_id NOT IN (:fileIds)
                            """)
                    .param("groupId", groupId)
                    .param("userId", identity.userId())
                    .param("fileIds", selected)
                    .query(Long.class)
                    .list();
        }
        for (Long fileId : removed) {
            jdbcClient.sql("""
                            UPDATE file_resource
                            SET status = 'DELETED'
                            WHERE file_id = :fileId
                            """)
                    .param("fileId", fileId)
                    .update();
        }
        for (Long fileId : selected) {
            jdbcClient.sql("""
                            UPDATE file_resource
                            SET case_group_id = :groupId,
                                order_id = NULL,
                                attachment_scope = 'SHARED',
                                source_type = 'CASE_GROUP_ATTACHMENT'
                            WHERE file_id = :fileId
                            """)
                    .param("groupId", groupId)
                    .param("fileId", fileId)
                    .update();
        }
        incrementDraftVersion(groupId, group.draftVersion());
        audit(
                groupId,
                null,
                "UPDATE_SHARED_FILES",
                Map.of("removed_file_ids", removed),
                Map.of("selected_file_ids", selected),
                identity.userId(),
                null,
                null);
        return caseGroupService.get(groupId, identity);
    }

    @Transactional
    public CaseGroupResponse submit(
            long groupId, SubmitCaseGroupRequest request, BootstrapIdentity identity) {
        LockedGroup group = lockOwnedGroup(groupId, identity);
        String submissionKey = request.idempotencyKey().trim();
        if ("SUBMITTED".equals(group.lifecycleStatus())) {
            if (submissionKey.equals(group.submissionIdempotencyKey())) {
                return caseGroupService.get(groupId, identity);
            }
            throw conflict("case group was already submitted with a different idempotency key");
        }
        if (!"DRAFT".equals(group.lifecycleStatus())) {
            throw conflict("case group is not submit-ready");
        }
        requireDraftVersion(group, request.expectedDraftVersion());
        List<DraftOrder> orders = loadDraftOrders(groupId);
        if (orders.isEmpty()) {
            throw badRequest("case group must contain at least one product");
        }

        LocalDate submittedOn = BusinessTime.today();
        for (DraftOrder order : orders) {
            ActiveProduct product = loadActiveProduct(order.productId(), order.variantId());
            ParsedFormData parsed = parseFormData(order.formData());
            SelectionValidation selection = validateSelections(
                    product,
                    parsed.materialSelections(),
                    parsed.accessorySelections(),
                    true);
            validateFormSchema(product, parsed.formValues(), true);
            validateUploadRules(product, group, order.orderId());
            // 提交时才强制回寄运单号：印模 / 返工 / 退货订单缺运单号会在这里抛 400，
            // 整个提交回滚——不能出现「一部分子订单进了初审、另一部分没进」的半提交状态。
            OrderRuleSelections selections = OrderRuleSelections.parse(parsed.formValues(), true);
            freezeSnapshot(order, product, parsed.formValues(), selection);
            orderRuleService.initializeOnSubmit(
                    order.orderId(),
                    product.workflowProductType(),
                    product.productName(),
                    selections,
                    submittedOn);
        }
        for (DraftOrder order : orders) {
            orderStatusService.updateOrderState(
                    order.orderId(),
                    InternalOrderStatus.PENDING_CS_REVIEW,
                    "DOCTOR_SUBMIT_CASE_GROUP_ITEM",
                    identity.userId(),
                    "doctor submitted case group " + group.groupNo());
        }
        int updated = jdbcClient.sql("""
                        UPDATE order_case_group
                        SET lifecycle_status = 'SUBMITTED',
                            submission_idempotency_key = :submissionKey,
                            submitted_draft_version = draft_version,
                            submitted_at = CURRENT_TIMESTAMP(3),
                            draft_version = draft_version + 1
                        WHERE group_id = :groupId
                          AND lifecycle_status = 'DRAFT'
                          AND draft_version = :draftVersion
                        """)
                .param("submissionKey", submissionKey)
                .param("groupId", groupId)
                .param("draftVersion", group.draftVersion())
                .update();
        if (updated == 0) {
            throw conflict("case group changed concurrently; refresh and retry");
        }
        audit(
                groupId,
                null,
                "SUBMIT_GROUP",
                Map.of("draft_version", group.draftVersion(), "lifecycle_status", "DRAFT"),
                Map.of("submitted_item_count", orders.size(), "lifecycle_status", "SUBMITTED"),
                identity.userId(),
                submissionKey,
                null);
        return caseGroupService.get(groupId, identity);
    }

    private SelectionValidation validateSelections(
            ActiveProduct product,
            List<QuantitySelection> requestedMaterials,
            List<QuantitySelection> requestedAccessories,
            boolean requireComplete) {
        List<QuantitySelection> materials = normalizedSelections(requestedMaterials, "material");
        List<QuantitySelection> accessories = normalizedSelections(requestedAccessories, "accessory");
        List<BindingRow> materialBindings = loadBindings(product, "MATERIAL");
        List<BindingRow> accessoryBindings = loadBindings(product, "ACCESSORY");
        validateBindingSelections(materialBindings, materials, "material", requireComplete);
        validateBindingSelections(accessoryBindings, accessories, "accessory", requireComplete);

        boolean pendingQuote = "PENDING_QUOTE".equals(product.pricingStatus())
                || product.basePriceCents() == null;
        long price = product.basePriceCents() == null ? 0 : product.basePriceCents();
        Map<Long, BindingRow> materialById = materialBindings.stream()
                .collect(Collectors.toMap(BindingRow::selectableId, Function.identity()));
        Map<Long, BindingRow> accessoryById = accessoryBindings.stream()
                .collect(Collectors.toMap(BindingRow::selectableId, Function.identity()));
        for (QuantitySelection selected : materials) {
            BindingRow binding = materialById.get(selected.itemId());
            pendingQuote |= binding.priceIncrementCents() == null;
            if (binding.priceIncrementCents() != null) {
                price = Math.addExact(price, Math.multiplyExact(
                        binding.priceIncrementCents(), selected.quantity().longValue()));
            }
        }
        for (QuantitySelection selected : accessories) {
            BindingRow binding = accessoryById.get(selected.itemId());
            pendingQuote |= binding.priceIncrementCents() == null;
            if (binding.priceIncrementCents() != null) {
                price = Math.addExact(price, Math.multiplyExact(
                        binding.priceIncrementCents(), selected.quantity().longValue()));
            }
        }
        PriceResult priceResult = pendingQuote
                ? new PriceResult("PENDING_QUOTE", null, product.currency(), "CATALOG_V2_PENDING_QUOTE")
                : new PriceResult("PRICED", price, product.currency(), "CATALOG_V2_VERSION_" + product.versionId());
        return new SelectionValidation(materials, accessories, materialBindings, accessoryBindings, priceResult);
    }

    private void validateBindingSelections(
            List<BindingRow> bindings,
            List<QuantitySelection> selections,
            String label,
            boolean requireComplete) {
        Map<Long, BindingRow> byId = bindings.stream()
                .collect(Collectors.toMap(BindingRow::selectableId, Function.identity()));
        for (QuantitySelection selected : selections) {
            BindingRow binding = byId.get(selected.itemId());
            if (binding == null) {
                throw badRequest(label + " is not applicable to the selected product or variant");
            }
            if (binding.minQuantity() != null && selected.quantity() < binding.minQuantity()) {
                throw badRequest(label + " quantity is below the configured minimum");
            }
            if (binding.maxQuantity() != null && selected.quantity() > binding.maxQuantity()) {
                throw badRequest(label + " quantity exceeds the configured maximum");
            }
        }
        Map<String, List<BindingRow>> groups = bindings.stream()
                .collect(Collectors.groupingBy(BindingRow::selectionGroupCode));
        Set<Long> selectedIds = selections.stream()
                .map(QuantitySelection::itemId)
                .collect(Collectors.toSet());
        for (Map.Entry<String, List<BindingRow>> entry : groups.entrySet()) {
            long selectedCount = entry.getValue().stream()
                    .filter(binding -> selectedIds.contains(binding.selectableId()))
                    .count();
            boolean required = entry.getValue().stream().anyMatch(BindingRow::required);
            boolean single = entry.getValue().stream()
                    .anyMatch(binding -> "SINGLE".equals(binding.selectionMode()));
            if (requireComplete && required && selectedCount == 0) {
                throw badRequest("required selection group is missing: " + entry.getKey());
            }
            if (single && selectedCount > 1) {
                throw badRequest("selection group only allows one option: " + entry.getKey());
            }
        }
    }

    private List<BindingRow> loadBindings(ActiveProduct product, String bindingType) {
        return catalogReader.loadBindings(product, bindingType);
    }

    private ActiveProduct loadActiveProduct(long productId, Long variantId) {
        return catalogReader.loadActiveProduct(productId, variantId);
    }

    private void validateFormSchema(ActiveProduct product, JsonNode values, boolean requireRequiredFields) {
        requireObject(values, "form_values");
        List<JsonNode> schemas = jdbcClient.sql("""
                        SELECT rule_schema_json
                        FROM catalog_rule_v2
                        WHERE config_version_id = :versionId
                          AND rule_type = 'FORM_SCHEMA'
                          AND status = 'ACTIVE'
                          AND (product_id IS NULL OR product_id = :productId)
                          AND (variant_id IS NULL OR variant_id <=> :variantId)
                        ORDER BY sort_order, rule_id
                        """)
                .param("versionId", product.versionId())
                .param("productId", product.productId())
                .param("variantId", product.variantId())
                .query((rs, rowNum) -> readJson(rs.getString("rule_schema_json")))
                .list();
        for (JsonNode schema : schemas) {
            JsonNode fields = schema.path("fields");
            if (!fields.isArray()) {
                continue;
            }
            for (JsonNode field : fields) {
                String key = field.path("key").asText("");
                if (key.isBlank() || !fieldVisible(field.path("visible_when"), values)) {
                    continue;
                }
                JsonNode value = values.get(key);
                if (requireRequiredFields && field.path("required").asBoolean(false) && missing(value)) {
                    throw badRequest("missing required form field: " + key);
                }
                if (!missing(value)) {
                    validateFormField(key, field, value);
                }
            }
        }
    }

    private boolean fieldVisible(JsonNode condition, JsonNode values) {
        if (!condition.isObject()) {
            return true;
        }
        String field = condition.path("field").asText("");
        JsonNode expected = condition.get("equals");
        return field.isBlank() || expected == null || expected.equals(values.get(field));
    }

    private void validateFormField(String key, JsonNode field, JsonNode value) {
        String type = field.path("type").asText("string").toLowerCase(Locale.ROOT);
        boolean valid = switch (type.toLowerCase(Locale.ROOT)) {
            case "string", "text", "textarea", "single_select", "color", "tooth" -> value.isTextual();
            case "number", "quantity" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array", "multi_select" -> value.isArray();
            case "object" -> value.isObject();
            default -> false;
        };
        if (!valid) {
            throw badRequest("invalid value type for form field: " + key);
        }
        if ("quantity".equals(type) && (!value.isIntegralNumber() || value.longValue() < 0)) {
            throw badRequest("quantity form field must be a non-negative integer: " + key);
        }
        validateAllowedOptions(key, type, field.path("options"), value);
        validateNumericBounds(key, field, value);
        validateCollectionBounds(key, field, value);
    }

    private void validateAllowedOptions(String key, String type, JsonNode options, JsonNode value) {
        if (!options.isArray() || options.isEmpty()) {
            return;
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (JsonNode option : options) {
            if (option.isTextual()) {
                allowed.add(option.asText());
            } else if (option.isObject() && option.hasNonNull("value")) {
                allowed.add(option.path("value").asText());
            }
        }
        if (allowed.isEmpty()) {
            return;
        }
        if (Set.of("array", "multi_select").contains(type)) {
            for (JsonNode selected : value) {
                if (!selected.isTextual() || !allowed.contains(selected.asText())) {
                    throw badRequest("form field contains an unsupported option: " + key);
                }
            }
        } else if (value.isTextual() && !allowed.contains(value.asText())) {
            throw badRequest("form field contains an unsupported option: " + key);
        }
    }

    private void validateNumericBounds(String key, JsonNode field, JsonNode value) {
        if (!value.isNumber()) {
            return;
        }
        BigDecimal actual = value.decimalValue();
        JsonNode minimum = field.has("minimum") ? field.get("minimum") : field.get("min");
        JsonNode maximum = field.has("maximum") ? field.get("maximum") : field.get("max");
        if (minimum != null && minimum.isNumber() && actual.compareTo(minimum.decimalValue()) < 0) {
            throw badRequest("form field is below the configured minimum: " + key);
        }
        if (maximum != null && maximum.isNumber() && actual.compareTo(maximum.decimalValue()) > 0) {
            throw badRequest("form field exceeds the configured maximum: " + key);
        }
    }

    private void validateCollectionBounds(String key, JsonNode field, JsonNode value) {
        int size;
        if (value.isArray()) {
            size = value.size();
        } else if (value.isTextual()) {
            size = value.textValue().length();
        } else {
            return;
        }
        JsonNode minimum = value.isArray()
                ? firstPresent(field, "min_items", "minItems")
                : firstPresent(field, "min_length", "minLength");
        JsonNode maximum = value.isArray()
                ? firstPresent(field, "max_items", "maxItems")
                : firstPresent(field, "max_length", "maxLength");
        if (minimum != null && minimum.canConvertToInt() && size < minimum.intValue()) {
            throw badRequest("form field has fewer values than configured: " + key);
        }
        if (maximum != null && maximum.canConvertToInt() && size > maximum.intValue()) {
            throw badRequest("form field has more values than configured: " + key);
        }
    }

    private static JsonNode firstPresent(JsonNode field, String primary, String fallback) {
        if (field.has(primary)) {
            return field.get(primary);
        }
        return field.get(fallback);
    }

    private void validateUploadRules(ActiveProduct product, LockedGroup group, long orderId) {
        List<JsonNode> rules = jdbcClient.sql("""
                        SELECT rule_schema_json
                        FROM catalog_rule_v2
                        WHERE config_version_id = :versionId
                          AND rule_type = 'UPLOAD'
                          AND status = 'ACTIVE'
                          AND (product_id IS NULL OR product_id = :productId)
                          AND (variant_id IS NULL OR variant_id <=> :variantId)
                        ORDER BY sort_order, rule_id
                        """)
                .param("versionId", product.versionId())
                .param("productId", product.productId())
                .param("variantId", product.variantId())
                .query((rs, rowNum) -> readJson(rs.getString("rule_schema_json")))
                .list();
        if (rules.isEmpty()) {
            return;
        }
        List<FileRow> files = jdbcClient.sql("""
                        SELECT file_id, original_filename, content_type, file_size
                        FROM file_resource
                        WHERE status = 'ACTIVE'
                          AND upload_status = 'COMPLETED'
                          AND case_group_id = :groupId
                          AND (attachment_scope = 'SHARED' OR order_id = :orderId)
                        """)
                .param("groupId", group.groupId())
                .param("orderId", orderId)
                .query((rs, rowNum) -> new FileRow(
                        rs.getLong("file_id"),
                        rs.getString("original_filename"),
                        rs.getString("content_type"),
                        rs.getObject("file_size", Long.class)))
                .list();
        for (JsonNode rule : rules) {
            int minFiles = rule.path("min_files").asInt(0);
            if (files.size() < minFiles) {
                throw badRequest("upload rule requires at least " + minFiles + " file(s)");
            }
            JsonNode extensions = rule.path("allowed_extensions");
            if (extensions.isArray() && !extensions.isEmpty()) {
                Set<String> allowed = new HashSet<>();
                extensions.forEach(value -> allowed.add(value.asText().toLowerCase(Locale.ROOT)));
                boolean allAllowed = files.stream().allMatch(file -> allowed.stream()
                        .anyMatch(extension -> file.originalFilename().toLowerCase(Locale.ROOT)
                                .endsWith(extension.startsWith(".") ? extension : "." + extension)));
                if (!allAllowed) {
                    throw badRequest("uploaded file type is not allowed by the product rule");
                }
            }
        }
    }

    private void freezeSnapshot(
            DraftOrder order,
            ActiveProduct product,
            JsonNode formValues,
            SelectionValidation selection) {
        ObjectNode productSnapshot = objectMapper.createObjectNode();
        productSnapshot.put("product_id", product.productId());
        productSnapshot.put("product_code", product.productCode());
        productSnapshot.put("display_name", product.productName());
        productSnapshot.put("category_code", product.categoryCode());
        productSnapshot.put("category_name", product.categoryName());
        productSnapshot.put("workflow_product_type", product.workflowProductType());
        productSnapshot.put("tooth_rule_code", product.toothRuleCode());
        if (product.variantId() != null) {
            productSnapshot.put("variant_id", product.variantId());
            productSnapshot.put("variant_code", product.variantCode());
            productSnapshot.put("variant_name", product.variantName());
        }
        productSnapshot.set("materials", selectedSnapshot(
                selection.materialSelections(), selection.materialBindings()));
        productSnapshot.set("accessories", selectedSnapshot(
                selection.accessorySelections(), selection.accessoryBindings()));
        JsonNode orthodonticSnapshot = orthodonticSnapshot(order.orderId(), product.workflowProductType());
        if (orthodonticSnapshot != null) {
            productSnapshot.set("orthodontic_prescription", orthodonticSnapshot);
        }

        ArrayNode ruleSnapshot = objectMapper.createArrayNode();
        jdbcClient.sql("""
                        SELECT rule_type, rule_code, rule_schema_json
                        FROM catalog_rule_v2
                        WHERE config_version_id = :versionId
                          AND status = 'ACTIVE'
                          AND (product_id IS NULL OR product_id = :productId)
                          AND (variant_id IS NULL OR variant_id <=> :variantId)
                        ORDER BY rule_type, sort_order, rule_id
                        """)
                .param("versionId", product.versionId())
                .param("productId", product.productId())
                .param("variantId", product.variantId())
                .query((rs, rowNum) -> {
                    ObjectNode node = objectMapper.createObjectNode();
                    node.put("rule_type", rs.getString("rule_type"));
                    node.put("rule_code", rs.getString("rule_code"));
                    node.set("schema", readJson(rs.getString("rule_schema_json")));
                    return node;
                })
                .list()
                .forEach(ruleSnapshot::add);

        ObjectNode priceSnapshot = objectMapper.createObjectNode();
        priceSnapshot.put("pricing_status", selection.price().pricingStatus());
        if (selection.price().priceCents() != null) {
            priceSnapshot.put("price_cents", selection.price().priceCents());
        } else {
            priceSnapshot.putNull("price_cents");
        }
        priceSnapshot.put("currency", selection.price().currency());
        priceSnapshot.put("pricing_source", selection.price().pricingSource());

        Map<String, Object> workflow = jdbcClient.sql("""
                        SELECT chain_id, chain_code, version, product_type
                        FROM workflow_chain
                        WHERE product_type = :productType
                          AND status = 1
                        ORDER BY version DESC, chain_id DESC
                        LIMIT 1
                        """)
                .param("productType", product.workflowProductType())
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "chain_id", rs.getLong("chain_id"),
                        "chain_code", rs.getString("chain_code"),
                        "chain_version", rs.getInt("version"),
                        "product_type", rs.getString("product_type")))
                .optional()
                .orElseThrow(() -> badRequest("active workflow chain is missing for selected product"));

        jdbcClient.sql("""
                        INSERT INTO order_catalog_snapshot
                            (order_id, config_version_id, product_snapshot,
                             form_schema_snapshot, normalized_form_values,
                             price_snapshot, workflow_mapping_snapshot)
                        VALUES
                            (:orderId, :versionId, CAST(:productSnapshot AS JSON),
                             CAST(:ruleSnapshot AS JSON), CAST(:formValues AS JSON),
                             CAST(:priceSnapshot AS JSON), CAST(:workflowSnapshot AS JSON))
                        """)
                .param("orderId", order.orderId())
                .param("versionId", product.versionId())
                .param("productSnapshot", json(productSnapshot))
                .param("ruleSnapshot", json(ruleSnapshot))
                .param("formValues", json(formValues))
                .param("priceSnapshot", json(priceSnapshot))
                .param("workflowSnapshot", json(workflow))
                .update();
        jdbcClient.sql("""
                        UPDATE orders
                        SET quoted_price_cents = :priceCents,
                            quoted_price_currency = :currency,
                            pricing_source = :pricingSource
                        WHERE order_id = :orderId
                        """)
                .param("priceCents", selection.price().priceCents())
                .param("currency", selection.price().currency())
                .param("pricingSource", selection.price().pricingSource())
                .param("orderId", order.orderId())
                .update();
    }

    private JsonNode orthodonticSnapshot(long orderId, String productType) {
        if (!Set.of("ORTHODONTICS", "ORTHODONTIC", "CLEAR_ALIGNER").contains(productType)) {
            return null;
        }
        try {
            return jdbcClient.sql("""
                            SELECT JSON_OBJECT(
                                'orthodontic_case_id', orthodontic_case_id,
                                'aligner_type_code', aligner_type_code,
                                'combined_order_id', combined_order_id,
                                'prescription_version', prescription_version,
                                'total_steps', total_steps,
                                'prescription', prescription_json
                            ) AS snapshot
                            FROM orthodontic_case
                            WHERE order_id = :orderId
                              AND case_status = 'PRESCRIPTION_SUBMITTED'
                            """)
                    .param("orderId", orderId)
                    .query((rs, rowNum) -> readJson(rs.getString("snapshot")))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw badRequest("orthodontic seven-step prescription must be submitted before the case group");
        }
    }

    private ArrayNode selectedSnapshot(
            List<QuantitySelection> selections, List<BindingRow> bindings) {
        Map<Long, BindingRow> byId = bindings.stream()
                .collect(Collectors.toMap(BindingRow::selectableId, Function.identity()));
        ArrayNode result = objectMapper.createArrayNode();
        for (QuantitySelection selection : selections) {
            BindingRow binding = byId.get(selection.itemId());
            ObjectNode node = objectMapper.createObjectNode();
            node.put("item_id", selection.itemId());
            node.put("item_code", binding.itemCode());
            node.put("display_name", binding.itemName());
            node.put("selection_group_code", binding.selectionGroupCode());
            node.put("quantity", selection.quantity());
            if (binding.priceIncrementCents() == null) {
                node.putNull("price_increment_cents");
            } else {
                node.put("price_increment_cents", binding.priceIncrementCents());
            }
            result.add(node);
        }
        return result;
    }

    private void synchronizeOrderFiles(
            LockedGroup group,
            long orderId,
            List<Long> requestedFileIds,
            BootstrapIdentity identity) {
        List<Long> selected = distinctIds(requestedFileIds);
        for (Long fileId : selected) {
            validateFile(fileId, group, orderId, identity);
        }
        List<Long> removed;
        if (selected.isEmpty()) {
            removed = jdbcClient.sql("""
                            SELECT file_id FROM file_resource
                            WHERE order_id = :orderId
                              AND owner_user_id = :userId
                              AND attachment_scope = 'ORDER'
                              AND status = 'ACTIVE'
                            """)
                    .param("orderId", orderId)
                    .param("userId", identity.userId())
                    .query(Long.class)
                    .list();
        } else {
            removed = jdbcClient.sql("""
                            SELECT file_id FROM file_resource
                            WHERE order_id = :orderId
                              AND owner_user_id = :userId
                              AND attachment_scope = 'ORDER'
                              AND status = 'ACTIVE'
                              AND file_id NOT IN (:fileIds)
                            """)
                    .param("orderId", orderId)
                    .param("userId", identity.userId())
                    .param("fileIds", selected)
                    .query(Long.class)
                    .list();
        }
        for (Long fileId : removed) {
            jdbcClient.sql("""
                            UPDATE file_resource
                            SET status = 'DELETED',
                                order_id = NULL
                            WHERE file_id = :fileId
                            """)
                    .param("fileId", fileId)
                    .update();
        }
        for (Long fileId : selected) {
            jdbcClient.sql("""
                            UPDATE file_resource
                            SET order_id = :orderId,
                                case_group_id = :groupId,
                                attachment_scope = 'ORDER',
                                source_type = 'ORDER_ATTACHMENT'
                            WHERE file_id = :fileId
                            """)
                    .param("orderId", orderId)
                    .param("groupId", group.groupId())
                    .param("fileId", fileId)
                    .update();
        }
    }

    private FileRow validateFile(
            long fileId,
            LockedGroup group,
            Long targetOrderId,
            BootstrapIdentity identity) {
        try {
            FileBindingRow file = jdbcClient.sql("""
                            SELECT file.file_id, file.order_id, file.case_group_id, file.owner_user_id,
                                   original_filename, content_type, file_size,
                                   visibility, upload_status, file.status,
                                   bound_order.group_id AS bound_order_group_id
                            FROM file_resource file
                            LEFT JOIN orders bound_order ON bound_order.order_id = file.order_id
                            WHERE file.file_id = :fileId
                            """)
                    .param("fileId", fileId)
                    .query((rs, rowNum) -> new FileBindingRow(
                            rs.getLong("file_id"),
                            rs.getObject("order_id", Long.class),
                            rs.getObject("case_group_id", Long.class),
                            rs.getObject("bound_order_group_id", Long.class),
                            rs.getObject("owner_user_id", Long.class),
                            rs.getString("original_filename"),
                            rs.getString("content_type"),
                            rs.getObject("file_size", Long.class),
                            rs.getString("visibility"),
                            rs.getString("upload_status"),
                            rs.getString("status")))
                    .single();
            if (!Objects.equals(identity.userId(), file.ownerUserId())
                    || !DOCTOR_FILE_VISIBILITY.contains(file.visibility())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "doctor cannot bind this file");
            }
            if (!"ACTIVE".equals(file.status()) || !"COMPLETED".equals(file.uploadStatus())) {
                throw conflict("file must be active and fully uploaded");
            }
            if (file.fileSize() != null && file.fileSize() > MAX_FILE_SIZE_BYTES) {
                throw badRequest("single file exceeds the 500MB limit");
            }
            if (file.caseGroupId() != null && !Objects.equals(file.caseGroupId(), group.groupId())) {
                throw conflict("file is already bound to another case group");
            }
            boolean movingOrderFileToSharedScope = targetOrderId == null
                    && Objects.equals(file.boundOrderGroupId(), group.groupId());
            if (file.orderId() != null
                    && !Objects.equals(file.orderId(), targetOrderId)
                    && !movingOrderFileToSharedScope) {
                throw conflict("file is already bound to another product order");
            }
            return new FileRow(file.fileId(), file.originalFilename(), file.contentType(), file.fileSize());
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "file not found", ex);
        }
    }

    private LockedGroup lockOwnedDraft(long groupId, BootstrapIdentity identity) {
        LockedGroup group = lockOwnedGroup(groupId, identity);
        if (!"DRAFT".equals(group.lifecycleStatus())) {
            throw conflict("submitted case groups are immutable");
        }
        return group;
    }

    private LockedGroup lockOwnedGroup(long groupId, BootstrapIdentity identity) {
        accessControlService.requireDoctorPortalAction(
                identity, "order:write-doctor", "only doctors can change case groups");
        accessControlService.requireScopedIdentity(identity, "CLINIC");
        try {
            LockedGroup group = jdbcClient.sql("""
                            SELECT group_id, group_no, clinic_id, doctor_user_id,
                                   patient_id, lifecycle_status, draft_version,
                                   submission_idempotency_key
                            FROM order_case_group
                            WHERE group_id = :groupId
                            FOR UPDATE
                            """)
                    .param("groupId", groupId)
                    .query((rs, rowNum) -> new LockedGroup(
                            rs.getLong("group_id"),
                            rs.getString("group_no"),
                            rs.getLong("clinic_id"),
                            rs.getObject("doctor_user_id", Long.class),
                            rs.getObject("patient_id", Long.class),
                            rs.getString("lifecycle_status"),
                            rs.getInt("draft_version"),
                            rs.getString("submission_idempotency_key")))
                    .single();
            if (!Objects.equals(group.doctorUserId(), identity.userId())
                    || !Objects.equals(group.clinicId(), identity.clinicId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "doctor cannot change this case group");
            }
            return group;
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "case group not found", ex);
        }
    }

    private DraftOrder loadDraftOrder(long groupId, long orderId) {
        try {
            return jdbcClient.sql("""
                            SELECT order_id, order_no, line_no, product_id, variant_id,
                                   item_client_key, form_data, internal_status
                            FROM orders
                            WHERE order_id = :orderId
                              AND group_id = :groupId
                              AND internal_status = 'DRAFT'
                            FOR UPDATE
                            """)
                    .param("orderId", orderId)
                    .param("groupId", groupId)
                    .query(CaseGroupDraftService::mapDraftOrder)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "draft product order not found", ex);
        }
    }

    private List<DraftOrder> loadDraftOrders(long groupId) {
        return jdbcClient.sql("""
                        SELECT order_id, order_no, line_no, product_id, variant_id,
                               item_client_key, form_data, internal_status
                        FROM orders
                        WHERE group_id = :groupId
                          AND internal_status = 'DRAFT'
                        ORDER BY line_no, order_id
                        FOR UPDATE
                        """)
                .param("groupId", groupId)
                .query(CaseGroupDraftService::mapDraftOrder)
                .list();
    }

    private DraftOrder findItemByClientKey(long groupId, String clientKey) {
        return jdbcClient.sql("""
                        SELECT order_id, order_no, line_no, product_id, variant_id,
                               item_client_key, form_data, internal_status
                        FROM orders
                        WHERE group_id = :groupId
                          AND item_client_key = :clientKey
                        """)
                .param("groupId", groupId)
                .param("clientKey", clientKey)
                .query(CaseGroupDraftService::mapDraftOrder)
                .optional()
                .orElse(null);
    }

    private static DraftOrder mapDraftOrder(ResultSet rs, int rowNum) throws SQLException {
        return new DraftOrder(
                rs.getLong("order_id"),
                rs.getString("order_no"),
                rs.getInt("line_no"),
                rs.getObject("product_id", Long.class),
                rs.getObject("variant_id", Long.class),
                rs.getString("item_client_key"),
                rs.getString("form_data"),
                rs.getString("internal_status"));
    }

    private void requireDraftVersion(LockedGroup group, int expectedVersion) {
        if (group.draftVersion() != expectedVersion) {
            throw conflict("case group changed concurrently; refresh and retry");
        }
    }

    private void incrementDraftVersion(long groupId, int expectedVersion) {
        int updated = jdbcClient.sql("""
                        UPDATE order_case_group
                        SET draft_version = draft_version + 1
                        WHERE group_id = :groupId
                          AND lifecycle_status = 'DRAFT'
                          AND draft_version = :expectedVersion
                        """)
                .param("groupId", groupId)
                .param("expectedVersion", expectedVersion)
                .update();
        if (updated == 0) {
            throw conflict("case group changed concurrently; refresh and retry");
        }
    }

    private int nextLineNo(long groupId) {
        return jdbcClient.sql("""
                        SELECT COALESCE(MAX(line_no), 0) + 1
                        FROM orders
                        WHERE group_id = :groupId
                        """)
                .param("groupId", groupId)
                .query(Integer.class)
                .single();
    }

    private long orderId(String orderNo) {
        return jdbcClient.sql("SELECT order_id FROM orders WHERE order_no = :orderNo")
                .param("orderNo", orderNo)
                .query(Long.class)
                .single();
    }

    private Map<String, Object> orderSnapshot(long orderId) {
        return jdbcClient.sql("""
                        SELECT order_id, group_id, line_no, relationship_type,
                               item_client_key, order_no, product_type, product_id,
                               variant_id, form_data, quoted_price_cents,
                               quoted_price_currency, pricing_source, internal_status
                        FROM orders
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    var meta = rs.getMetaData();
                    for (int index = 1; index <= meta.getColumnCount(); index++) {
                        row.put(meta.getColumnLabel(index), rs.getObject(index));
                    }
                    return row;
                })
                .single();
    }

    private String buildFormData(
            JsonNode formValues,
            List<QuantitySelection> materialSelections,
            List<QuantitySelection> accessorySelections) {
        ObjectNode result = objectMapper.createObjectNode();
        result.set("form_values", formValues.deepCopy());
        result.set("material_selections", objectMapper.valueToTree(materialSelections));
        result.set("accessory_selections", objectMapper.valueToTree(accessorySelections));
        return json(result);
    }

    private ParsedFormData parseFormData(String rawJson) {
        JsonNode root = readJson(rawJson);
        JsonNode formValues = root.path("form_values");
        requireObject(formValues, "form_values");
        return new ParsedFormData(
                formValues,
                readSelections(root.path("material_selections")),
                readSelections(root.path("accessory_selections")));
    }

    private List<QuantitySelection> readSelections(JsonNode value) {
        if (!value.isArray()) {
            return List.of();
        }
        List<QuantitySelection> result = new ArrayList<>();
        for (JsonNode item : value) {
            result.add(new QuantitySelection(
                    item.path("item_id").longValue(),
                    item.path("quantity").intValue()));
        }
        return result;
    }

    private List<QuantitySelection> normalizedSelections(
            List<QuantitySelection> requested, String label) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        Map<Long, QuantitySelection> distinct = new LinkedHashMap<>();
        for (QuantitySelection selection : requested) {
            if (selection.quantity() == null || selection.quantity() <= 0) {
                throw badRequest(label + " quantity must be greater than zero");
            }
            if (distinct.putIfAbsent(selection.itemId(), selection) != null) {
                throw badRequest("duplicate " + label + " selection");
            }
        }
        return List.copyOf(distinct.values());
    }

    private List<Long> distinctIds(List<Long> ids) {
        return ids == null ? List.of() : List.copyOf(new LinkedHashSet<>(ids));
    }

    private String relationship(String requested, int lineNo) {
        if (requested != null && !requested.isBlank()) {
            if (lineNo > 1 && "PRIMARY".equals(requested)) {
                return "RELATED";
            }
            return requested;
        }
        return lineNo == 1 ? "PRIMARY" : "RELATED";
    }

    private boolean missing(JsonNode value) {
        return value == null
                || value.isNull()
                || (value.isTextual() && value.asText().isBlank())
                || (value.isArray() && value.isEmpty());
    }

    private void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw badRequest(field + " must be an object");
        }
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value == null ? "{}" : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("stored JSON is invalid", ex);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize order group value", ex);
        }
    }

    private void audit(
            long groupId,
            Long orderId,
            String action,
            Object before,
            Object after,
            Long operatorUserId,
            String idempotencyKey,
            String reason) {
        jdbcClient.sql("""
                        INSERT INTO order_case_group_audit
                            (group_id, order_id, action_type, before_value,
                             after_value, operator_user_id, idempotency_key, reason)
                        VALUES
                            (:groupId, :orderId, :action, CAST(:beforeValue AS JSON),
                             CAST(:afterValue AS JSON), :operatorUserId, :idempotencyKey, :reason)
                        """)
                .param("groupId", groupId)
                .param("orderId", orderId)
                .param("action", action)
                .param("beforeValue", before == null ? null : json(before))
                .param("afterValue", after == null ? null : json(after))
                .param("operatorUserId", operatorUserId)
                .param("idempotencyKey", idempotencyKey)
                .param("reason", reason)
                .update();
    }

    private String nextOrderNo() {
        String date = BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 10)
                .toUpperCase(Locale.ROOT);
        return "ORD" + date + "-" + suffix;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record LockedGroup(
            long groupId,
            String groupNo,
            Long clinicId,
            Long doctorUserId,
            Long patientId,
            String lifecycleStatus,
            int draftVersion,
            String submissionIdempotencyKey) {
    }

    private record DraftOrder(
            long orderId,
            String orderNo,
            int lineNo,
            Long productId,
            Long variantId,
            String itemClientKey,
            String formData,
            String internalStatus) {
    }

    private record PriceResult(
            String pricingStatus,
            Long priceCents,
            String currency,
            String pricingSource) {
    }

    private record SelectionValidation(
            List<QuantitySelection> materialSelections,
            List<QuantitySelection> accessorySelections,
            List<BindingRow> materialBindings,
            List<BindingRow> accessoryBindings,
            PriceResult price) {
    }

    private record ParsedFormData(
            JsonNode formValues,
            List<QuantitySelection> materialSelections,
            List<QuantitySelection> accessorySelections) {
    }

    private record FileBindingRow(
            long fileId,
            Long orderId,
            Long caseGroupId,
            Long boundOrderGroupId,
            Long ownerUserId,
            String originalFilename,
            String contentType,
            Long fileSize,
            String visibility,
            String uploadStatus,
            String status) {
    }

    private record FileRow(
            long fileId,
            String originalFilename,
            String contentType,
            Long fileSize) {
    }
}
