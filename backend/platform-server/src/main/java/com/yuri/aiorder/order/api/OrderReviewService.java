package com.yuri.aiorder.order.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.notification.NotificationPushService;
import com.yuri.aiorder.order.status.InternalOrderStatus;
import com.yuri.aiorder.order.status.OrderStatusService;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderReviewService {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final OrderStatusService statusService;
    private final OrderProjectionQueryService queryService;
    private final NotificationPushService notificationPushService;

    public OrderReviewService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            OrderStatusService statusService,
            OrderProjectionQueryService queryService,
            NotificationPushService notificationPushService) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.statusService = statusService;
        this.queryService = queryService;
        this.notificationPushService = notificationPushService;
    }

    @Transactional
    public OrderInternalDTO review(long orderId, OrderReviewRequest request, BootstrapIdentity identity) {
        OrderReviewRow order = lockReviewableOrder(orderId, identity);
        String action = normalize(request.action());
        if ("APPROVE".equals(action)) {
            approve(order, request, identity);
        } else if ("REJECT".equals(action)) {
            reject(order, request, identity);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported order review action");
        }
        return queryService.getInternalOrder(orderId, identity);
    }

    private void approve(OrderReviewRow order, OrderReviewRequest request, BootstrapIdentity identity) {
        jdbcClient.sql("""
                        UPDATE orders
                        SET production_note = :productionNote,
                            reject_reason = NULL,
                            cs_user_id = COALESCE(:reviewerUserId, cs_user_id)
                        WHERE order_id = :orderId
                        """)
                .param("productionNote", blankToNull(request.productionNote()))
                .param("reviewerUserId", identity.userId())
                .param("orderId", order.orderId())
                .update();
        statusService.updateOrderState(
                order.orderId(),
                InternalOrderStatus.PENDING_PRODUCTION_REVIEW,
                "CS_APPROVE_ORDER",
                identity.userId(),
                "客服初审通过，进入生产审核。");
        emit(order, "ORDER_APPROVED", "DOCTOR", order.doctorUserId(), "客服审核通过，等待生产审核。");
    }

    private void reject(OrderReviewRow order, OrderReviewRequest request, BootstrapIdentity identity) {
        String rejectReason = blankToNull(request.rejectReason());
        if (rejectReason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reject_reason is required when rejecting order");
        }
        jdbcClient.sql("""
                        UPDATE orders
                        SET reject_reason = :rejectReason,
                            cs_user_id = COALESCE(:reviewerUserId, cs_user_id)
                        WHERE order_id = :orderId
                        """)
                .param("rejectReason", rejectReason)
                .param("reviewerUserId", identity.userId())
                .param("orderId", order.orderId())
                .update();
        statusService.updateOrderState(
                order.orderId(),
                InternalOrderStatus.CS_REJECTED,
                "CS_REJECT_ORDER",
                identity.userId(),
                rejectReason);
        emit(order, "ORDER_REJECTED", "DOCTOR", order.doctorUserId(), "客服审核驳回，请补充资料。");
    }

    private OrderReviewRow lockReviewableOrder(long orderId, BootstrapIdentity identity) {
        queryService.getInternalOrder(orderId, identity);
        OrderReviewRow order = jdbcClient.sql("""
                        SELECT order_id, order_no, doctor_user_id, internal_status
                        FROM orders
                        WHERE order_id = :orderId
                        FOR UPDATE
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new OrderReviewRow(
                        rs.getLong("order_id"),
                        rs.getString("order_no"),
                        rs.getObject("doctor_user_id", Long.class),
                        rs.getString("internal_status")))
                .single();
        if (!InternalOrderStatus.PENDING_CS_REVIEW.name().equals(order.internalStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "order is not pending CS review");
        }
        return order;
    }

    private void emit(OrderReviewRow order, String eventType, String audienceRole, Long userId, String message) {
        String payload = payload(order, eventType, message);
        jdbcClient.sql("""
                        INSERT INTO notification_event
                            (order_id, event_type, audience_role, payload, delivery_status)
                        VALUES
                            (:orderId, :eventType, :audienceRole, CAST(:payload AS JSON), 'PENDING')
                        """)
                .param("orderId", order.orderId())
                .param("eventType", eventType)
                .param("audienceRole", audienceRole)
                .param("payload", payload)
                .update();
        long eventId = jdbcClient.sql("SELECT LAST_INSERT_ID()")
                .query(Long.class)
                .single();
        if (userId == null) {
            return;
        }
        jdbcClient.sql("""
                        INSERT IGNORE INTO user_notification (event_id, user_id)
                        VALUES (:eventId, :userId)
                        """)
                .param("eventId", eventId)
                .param("userId", userId)
                .update();
        notificationPushService.pushToUser(userId, eventId, payload);
    }

    private String payload(OrderReviewRow order, String eventType, String message) {
        try {
            return objectMapper.writeValueAsString(new NotificationPayload(
                    eventType, order.orderId(), order.orderNo(), message));
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to build notification payload", ex);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record OrderReviewRow(long orderId, String orderNo, Long doctorUserId, String internalStatus) {
    }

    private record NotificationPayload(String event, long orderId, String orderNo, String message) {
    }
}
