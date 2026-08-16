package com.yuri.aiorder.order.status;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderStatusService {

    private final JdbcClient jdbcClient;
    private final OrderStatusProjector projector;

    public OrderStatusService(JdbcClient jdbcClient, OrderStatusProjector projector) {
        this.jdbcClient = jdbcClient;
        this.projector = projector;
    }

    @Transactional
    public ExternalOrderStatus updateOrderState(
            long orderId,
            InternalOrderStatus targetStatus,
            String eventType,
            Long operatorUserId,
            String reason) {
        OrderStatusSnapshot current = lockOrder(orderId);
        ExternalOrderStatus targetExternalStatus = projector.project(targetStatus);

        jdbcClient.sql("""
                        UPDATE orders
                        SET internal_status = :internalStatus,
                            external_status = :externalStatus
                        WHERE order_id = :orderId
                        """)
                .param("internalStatus", targetStatus.name())
                .param("externalStatus", targetExternalStatus.name())
                .param("orderId", orderId)
                .update();

        jdbcClient.sql("""
                        INSERT INTO order_status_history
                            (order_id, from_internal_status, to_internal_status,
                             from_external_status, to_external_status, event_type,
                             operator_user_id, reason)
                        VALUES
                            (:orderId, :fromInternalStatus, :toInternalStatus,
                             :fromExternalStatus, :toExternalStatus, :eventType,
                             :operatorUserId, :reason)
                        """)
                .param("orderId", orderId)
                .param("fromInternalStatus", current.internalStatus())
                .param("toInternalStatus", targetStatus.name())
                .param("fromExternalStatus", current.externalStatus())
                .param("toExternalStatus", targetExternalStatus.name())
                .param("eventType", eventType)
                .param("operatorUserId", operatorUserId)
                .param("reason", reason)
                .update();

        return projector.refresh(orderId);
    }

    private OrderStatusSnapshot lockOrder(long orderId) {
        return jdbcClient.sql("""
                        SELECT internal_status, external_status
                        FROM orders
                        WHERE order_id = :orderId
                        FOR UPDATE
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new OrderStatusSnapshot(
                        rs.getString("internal_status"),
                        rs.getString("external_status")))
                .single();
    }

    private record OrderStatusSnapshot(String internalStatus, String externalStatus) {
    }
}
