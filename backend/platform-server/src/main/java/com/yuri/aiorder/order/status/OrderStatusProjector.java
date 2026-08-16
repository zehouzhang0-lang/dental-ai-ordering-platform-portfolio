package com.yuri.aiorder.order.status;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class OrderStatusProjector {

    private final JdbcClient jdbcClient;

    public OrderStatusProjector(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public ExternalOrderStatus project(InternalOrderStatus internalStatus) {
        return internalStatus.externalStatus();
    }

    public ExternalOrderStatus refresh(long orderId) {
        InternalOrderStatus internalStatus = jdbcClient.sql("""
                        SELECT internal_status
                        FROM orders
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> InternalOrderStatus.valueOf(rs.getString("internal_status")))
                .single();

        ExternalOrderStatus externalStatus = project(internalStatus);
        String publicMessage = publicMessage(externalStatus);

        jdbcClient.sql("""
                        UPDATE orders
                        SET external_status = :externalStatus,
                            version = version + 1
                        WHERE order_id = :orderId
                        """)
                .param("externalStatus", externalStatus.name())
                .param("orderId", orderId)
                .update();

        jdbcClient.sql("""
                        INSERT INTO order_external_projection
                            (order_id, external_status, public_message)
                        VALUES
                            (:orderId, :externalStatus, :publicMessage)
                        ON DUPLICATE KEY UPDATE
                            external_status = VALUES(external_status),
                            public_message = VALUES(public_message),
                            updated_at = CURRENT_TIMESTAMP(3)
                        """)
                .param("orderId", orderId)
                .param("externalStatus", externalStatus.name())
                .param("publicMessage", publicMessage)
                .update();

        return externalStatus;
    }

    public String publicMessage(ExternalOrderStatus status) {
        return switch (status) {
            case PENDING_REVIEW -> "订单已提交，正在审核资料。";
            case DESIGNING -> "订单已通过审核，正在进行设计相关工作。";
            case PRODUCING -> "订单正在生产中。";
            case QC -> "订单正在质检中。";
            case PENDING_SHIP -> "订单已通过质检，等待发货。";
            case SHIPPED -> "订单已发货，请关注物流信息。";
            case COMPLETED -> "订单已完成。";
        };
    }
}
