package com.yuri.aiorder.order.rules;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuri.aiorder.catalog.ActiveCatalogProductReader;
import com.yuri.aiorder.catalog.ActiveCatalogProductReader.ActiveProduct;
import com.yuri.aiorder.catalog.ActiveCatalogProductReader.BindingRow;
import com.yuri.aiorder.order.rules.OrderRuleModels.FinalizeTryInRequest;
import com.yuri.aiorder.order.rules.OrderRuleModels.MaterialSelection;
import com.yuri.aiorder.order.rules.OrderRuleModels.TryInResponse;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 试戴。
 *
 * <p>客户原话：「试戴作为独立计价项落入账单；试戴完成后同一订单可继续选择成品与材料，不新建订单。」
 * 因此 {@link #finalizeSelection} 更新的是**同一个 order_id**——订单号不变，历史与工序都留在原处。
 *
 * <p>状态机（试戴域，与订单状态不是一个域）：
 * <pre>
 *   REQUESTED  下单时勾了试戴
 *      ▼ 生产/客服登记试戴完成
 *   COMPLETED  医生可以在原订单上选成品与材料
 *      ▼ 医生选定
 *   FINALIZED
 * </pre>
 */
@Service
public class TryInService {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final ActiveCatalogProductReader catalogReader;
    private final OrderBillItemService billItemService;
    private final DeliveryPlanService deliveryPlanService;

    public TryInService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            ActiveCatalogProductReader catalogReader,
            OrderBillItemService billItemService,
            DeliveryPlanService deliveryPlanService) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.catalogReader = catalogReader;
        this.billItemService = billItemService;
        this.deliveryPlanService = deliveryPlanService;
    }

    @Transactional
    public void initialize(long orderId, boolean tryInRequired) {
        if (!tryInRequired) {
            // 医生取消勾选：只删还没开始的记录，已完成的试戴是既成事实，不抹掉。
            jdbcClient.sql("""
                            DELETE FROM order_try_in
                            WHERE order_id = :orderId
                              AND try_in_status = 'REQUESTED'
                            """)
                    .param("orderId", orderId)
                    .update();
            billItemService.removeTryInItem(orderId);
            return;
        }
        jdbcClient.sql("""
                        INSERT INTO order_try_in (order_id, try_in_status)
                        VALUES (:orderId, 'REQUESTED')
                        ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP(3)
                        """)
                .param("orderId", orderId)
                .update();
        billItemService.generateTryInItem(orderId);
    }

    @Transactional
    public TryInRow complete(long orderId, String note, Long operatorUserId) {
        TryInRow tryIn = lock(orderId);
        if (OrderRuleVocabulary.TRY_IN_FINALIZED.equals(tryIn.tryInStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "try-in was already finalized for this order");
        }
        jdbcClient.sql("""
                        UPDATE order_try_in
                        SET try_in_status = 'COMPLETED',
                            completed_at = COALESCE(completed_at, CURRENT_TIMESTAMP(3)),
                            completed_by_user_id = COALESCE(completed_by_user_id, :operatorUserId),
                            finalize_note = COALESCE(:note, finalize_note)
                        WHERE order_id = :orderId
                        """)
                .param("operatorUserId", operatorUserId)
                .param("note", note == null || note.isBlank() ? null : note.trim())
                .param("orderId", orderId)
                .update();
        return find(orderId);
    }

    /** 试戴完成后医生在同一订单上选定成品与材料。不新建订单。 */
    @Transactional
    public TryInRow finalizeSelection(long orderId, FinalizeTryInRequest request, Long operatorUserId) {
        TryInRow tryIn = lock(orderId);
        if (!OrderRuleVocabulary.TRY_IN_COMPLETED.equals(tryIn.tryInStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "final product can only be selected after the try-in is marked complete");
        }
        String productName = null;
        if (request != null && request.productId() != null) {
            ActiveProduct product =
                    catalogReader.loadActiveProduct(request.productId(), request.variantId());
            List<MaterialSelection> materials = normalizeMaterials(
                    request.materialSelections() == null ? List.of() : request.materialSelections());
            Price price = priceOf(product, materials);
            productName = product.productName();
            jdbcClient.sql("""
                            UPDATE orders
                            SET product_id = :productId,
                                variant_id = :variantId,
                                product_type = :productType,
                                form_data = :formData,
                                quoted_price_cents = :priceCents,
                                quoted_price_currency = :currency,
                                pricing_source = :pricingSource
                            WHERE order_id = :orderId
                            """)
                    .param("productId", product.productId())
                    .param("variantId", product.variantId())
                    .param("productType", product.workflowProductType())
                    .param("formData", mergedFormData(orderId, materials))
                    .param("priceCents", price.priceCents())
                    .param("currency", price.currency())
                    .param("pricingSource", price.pricingSource())
                    .param("orderId", orderId)
                    .update();
        }
        String note = request == null || request.note() == null || request.note().isBlank()
                ? null
                : request.note().trim();
        jdbcClient.sql("""
                        UPDATE order_try_in
                        SET try_in_status = 'FINALIZED',
                            finalized_at = CURRENT_TIMESTAMP(3),
                            finalized_by_user_id = :operatorUserId,
                            finalize_note = COALESCE(:note, finalize_note)
                        WHERE order_id = :orderId
                        """)
                .param("operatorUserId", operatorUserId)
                .param("note", note)
                .param("orderId", orderId)
                .update();
        billItemService.updateProductItemAfterTryIn(orderId, productName, "试戴后确认的成品");
        // 成品换了，产品类型可能也换了，标准制作周期跟着变——重算而不是沿用提交时的交期。
        deliveryPlanService.recompute(orderId);
        return find(orderId);
    }

    public TryInRow find(long orderId) {
        return jdbcClient.sql("""
                        SELECT try_in_id, order_id, try_in_status, completed_at,
                               finalized_at, finalize_note
                        FROM order_try_in
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(TryInService::mapTryIn)
                .optional()
                .orElse(null);
    }

    public TryInResponse toResponse(long orderId) {
        TryInRow row = find(orderId);
        if (row == null) {
            return new TryInResponse(orderId, false, null, null, null, null, false);
        }
        return new TryInResponse(
                orderId,
                true,
                row.tryInStatus(),
                row.completedAt(),
                row.finalizedAt(),
                row.finalizeNote(),
                OrderRuleVocabulary.TRY_IN_COMPLETED.equals(row.tryInStatus()));
    }

    private TryInRow lock(long orderId) {
        return jdbcClient.sql("""
                        SELECT try_in_id, order_id, try_in_status, completed_at,
                               finalized_at, finalize_note
                        FROM order_try_in
                        WHERE order_id = :orderId
                        FOR UPDATE
                        """)
                .param("orderId", orderId)
                .query(TryInService::mapTryIn)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "this order did not request a try-in"));
    }

    private List<MaterialSelection> normalizeMaterials(List<MaterialSelection> requested) {
        Map<Long, MaterialSelection> distinct = new LinkedHashMap<>();
        for (MaterialSelection selection : requested) {
            if (selection.itemId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "material item_id is required");
            }
            int quantity = selection.quantity() == null ? 1 : selection.quantity();
            if (quantity <= 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "material quantity must be greater than zero");
            }
            if (distinct.putIfAbsent(
                    selection.itemId(), new MaterialSelection(selection.itemId(), quantity)) != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "duplicate material selection");
            }
        }
        return List.copyOf(distinct.values());
    }

    private Price priceOf(ActiveProduct product, List<MaterialSelection> materials) {
        Map<Long, BindingRow> bindings = catalogReader.loadBindings(product, "MATERIAL").stream()
                .collect(Collectors.toMap(BindingRow::selectableId, Function.identity()));
        boolean pendingQuote = "PENDING_QUOTE".equals(product.pricingStatus())
                || product.basePriceCents() == null;
        long price = product.basePriceCents() == null ? 0 : product.basePriceCents();
        for (MaterialSelection selection : materials) {
            BindingRow binding = bindings.get(selection.itemId());
            if (binding == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "material is not applicable to the selected product or variant");
            }
            if (binding.priceIncrementCents() == null) {
                pendingQuote = true;
                continue;
            }
            price = Math.addExact(
                    price, Math.multiplyExact(binding.priceIncrementCents(), (long) selection.quantity()));
        }
        return pendingQuote
                ? new Price(null, product.currency(), "CATALOG_V2_PENDING_QUOTE")
                : new Price(price, product.currency(), "CATALOG_V2_VERSION_" + product.versionId());
    }

    /**
     * 只替换 {@code material_selections}，{@code form_values} 原样保留——试戴前填的牙位、
     * 特殊要求、订单类型都仍然有效，不能因为换了成品就丢掉。
     */
    private String mergedFormData(long orderId, List<MaterialSelection> materials) {
        String raw = jdbcClient.sql("SELECT form_data FROM orders WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .optional()
                .orElse("{}");
        try {
            JsonNode parsed = objectMapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            ObjectNode root = parsed.isObject()
                    ? (ObjectNode) parsed.deepCopy()
                    : objectMapper.createObjectNode();
            if (!root.has("form_values")) {
                root.set("form_values", objectMapper.createObjectNode());
            }
            ArrayNode selections = objectMapper.createArrayNode();
            for (MaterialSelection material : materials) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("item_id", material.itemId());
                node.put("quantity", material.quantity());
                selections.add(node);
            }
            root.set("material_selections", selections);
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "stored order form data is invalid", ex);
        }
    }

    private static TryInRow mapTryIn(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new TryInRow(
                rs.getLong("try_in_id"),
                rs.getLong("order_id"),
                rs.getString("try_in_status"),
                rs.getObject("completed_at", LocalDateTime.class),
                rs.getObject("finalized_at", LocalDateTime.class),
                rs.getString("finalize_note"));
    }

    public record TryInRow(
            long tryInId,
            long orderId,
            String tryInStatus,
            LocalDateTime completedAt,
            LocalDateTime finalizedAt,
            String finalizeNote) {
    }

    private record Price(Long priceCents, String currency, String pricingSource) {
    }
}
