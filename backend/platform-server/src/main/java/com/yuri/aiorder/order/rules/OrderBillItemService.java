package com.yuri.aiorder.order.rules;

import com.yuri.aiorder.order.rules.OrderRuleModels.BillItemResponse;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账单计价项。
 *
 * <p>{@code order_bill} 的语义不变：客服上传的账单 PDF 与总额。这里生成的是**系统按下单规则得出的计价项**，
 * 供客服核价时对照，也让「勾了试戴到底算不算钱」这件事在数据里有据可查。
 *
 * <p>价格属客户未提供项，因此产品项取目录报价（多为 {@code PENDING_QUOTE}），
 * 试戴项一律 {@code PENDING_QUOTE} 且不预填金额——按客户原话「试戴费用待报价，不预填金额」。
 */
@Service
public class OrderBillItemService {

    public static final String ITEM_PRODUCT = "PRODUCT";
    public static final String ITEM_TRY_IN = "TRY_IN";

    private final JdbcClient jdbcClient;

    public OrderBillItemService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public void generateProductItem(long orderId, String productName) {
        upsert(
                orderId,
                ITEM_PRODUCT,
                productName == null || productName.isBlank() ? "定制产品" : productName,
                "ORDER_SUBMIT",
                10,
                null);
    }

    /** 试戴是独立计价项，与成品分开列。 */
    @Transactional
    public void generateTryInItem(long orderId) {
        upsert(orderId, ITEM_TRY_IN, "试戴", "ORDER_SUBMIT", 20, "试戴费用待客服核价");
    }

    @Transactional
    public void removeTryInItem(long orderId) {
        jdbcClient.sql("""
                        DELETE FROM order_bill_item
                        WHERE order_id = :orderId
                          AND item_code = :itemCode
                        """)
                .param("orderId", orderId)
                .param("itemCode", ITEM_TRY_IN)
                .update();
    }

    /** 试戴完成后医生在同一订单上选定成品，产品计价项按新的成品名称更新。 */
    @Transactional
    public void updateProductItemAfterTryIn(long orderId, String productName, String remark) {
        upsert(
                orderId,
                ITEM_PRODUCT,
                productName == null || productName.isBlank() ? "定制产品" : productName,
                "TRY_IN_FINALIZE",
                10,
                remark);
    }

    public List<BillItemResponse> list(long orderId) {
        return jdbcClient.sql("""
                        SELECT bill_item_id, order_id, item_code, item_name, quantity,
                               pricing_status, amount_cents, currency, source_type, remark
                        FROM order_bill_item
                        WHERE order_id = :orderId
                        ORDER BY sort_order, bill_item_id
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new BillItemResponse(
                        rs.getLong("bill_item_id"),
                        rs.getLong("order_id"),
                        rs.getString("item_code"),
                        rs.getString("item_name"),
                        rs.getInt("quantity"),
                        rs.getString("pricing_status"),
                        rs.getObject("amount_cents", Long.class),
                        rs.getString("currency"),
                        rs.getString("source_type"),
                        rs.getString("remark")))
                .list();
    }

    /**
     * 金额取订单上冻结的目录报价：{@code quoted_price_cents} 为空即 {@code PENDING_QUOTE}。
     * 试戴项没有目录价，因此显式传 {@code useQuotedPrice = false}。
     */
    private void upsert(
            long orderId, String itemCode, String itemName, String sourceType, int sortOrder, String remark) {
        boolean useQuotedPrice = ITEM_PRODUCT.equals(itemCode);
        jdbcClient.sql("""
                        INSERT INTO order_bill_item
                            (order_id, item_code, item_name, quantity, pricing_status,
                             amount_cents, currency, source_type, remark, sort_order)
                        SELECT
                            o.order_id, :itemCode, :itemName, 1,
                            CASE WHEN :useQuotedPrice AND o.quoted_price_cents IS NOT NULL
                                 THEN 'PRICED' ELSE 'PENDING_QUOTE' END,
                            CASE WHEN :useQuotedPrice THEN o.quoted_price_cents ELSE NULL END,
                            COALESCE(o.quoted_price_currency, 'CNY'),
                            :sourceType, :remark, :sortOrder
                        FROM orders o
                        WHERE o.order_id = :orderId
                        ON DUPLICATE KEY UPDATE
                            item_name = VALUES(item_name),
                            pricing_status = VALUES(pricing_status),
                            amount_cents = VALUES(amount_cents),
                            currency = VALUES(currency),
                            source_type = VALUES(source_type),
                            remark = VALUES(remark),
                            sort_order = VALUES(sort_order)
                        """)
                .param("itemCode", itemCode)
                .param("itemName", itemName)
                .param("useQuotedPrice", useQuotedPrice)
                .param("sourceType", sourceType)
                .param("remark", remark)
                .param("sortOrder", sortOrder)
                .param("orderId", orderId)
                .update();
    }
}
