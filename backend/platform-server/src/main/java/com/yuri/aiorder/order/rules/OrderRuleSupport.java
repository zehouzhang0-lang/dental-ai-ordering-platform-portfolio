package com.yuri.aiorder.order.rules;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.auth.AccessControlService;
import com.yuri.aiorder.notification.NotificationPushService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 下单规则相关服务共用的订单归属判定与通知投递。
 *
 * <p>数据范围判定与 {@code CollaborationService} / {@code OrderProjectionQueryService} 保持同一套口径：
 * 一份订单能不能被看见由 {@code data_scope} 决定，而不是由入口角色决定。
 */
@Component
public class OrderRuleSupport {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;
    private final NotificationPushService notificationPushService;

    public OrderRuleSupport(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            AccessControlService accessControlService,
            NotificationPushService notificationPushService) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
        this.notificationPushService = notificationPushService;
    }

    public OrderRow loadScopedOrder(long orderId, BootstrapIdentity identity, String forbiddenMessage) {
        String dataScope = accessControlService.effectiveDataScope(identity);
        accessControlService.requireScopedIdentity(identity, dataScope);
        try {
            return jdbcClient.sql("""
                            SELECT order_id, order_no, clinic_id, doctor_user_id, cs_user_id,
                                   product_type, internal_status, created_at
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
                    .query(OrderRuleSupport::mapOrder)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            if (exists(orderId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, forbiddenMessage, ex);
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found", ex);
        }
    }

    public OrderRow loadOrder(long orderId) {
        try {
            return jdbcClient.sql("""
                            SELECT order_id, order_no, clinic_id, doctor_user_id, cs_user_id,
                                   product_type, internal_status, created_at
                            FROM orders
                            WHERE order_id = :orderId
                            """)
                    .param("orderId", orderId)
                    .query(OrderRuleSupport::mapOrder)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found", ex);
        }
    }

    /** 医生只能操作自己的订单；内部角色由 {@link #loadScopedOrder} 的数据范围已经管住。 */
    public void requireDoctorOwnership(OrderRow order, BootstrapIdentity identity) {
        if (identity.isDoctor()
                && (identity.userId() == null || !identity.userId().equals(order.doctorUserId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "doctor cannot access this order");
        }
    }

    public void emit(OrderRow order, String eventType, String audienceRole, Long userId, String message) {
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
        if (userId == null) {
            return;
        }
        long eventId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jdbcClient.sql("""
                        INSERT IGNORE INTO user_notification (event_id, user_id)
                        VALUES (:eventId, :userId)
                        """)
                .param("eventId", eventId)
                .param("userId", userId)
                .update();
        notificationPushService.pushToUser(userId, eventId, payload);
    }

    private String payload(OrderRow order, String eventType, String message) {
        try {
            return objectMapper.writeValueAsString(
                    new NotificationPayload(eventType, order.orderId(), order.orderNo(), message));
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "failed to build notification payload", ex);
        }
    }

    private boolean exists(long orderId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM orders WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single() > 0;
    }

    private static OrderRow mapOrder(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new OrderRow(
                rs.getLong("order_id"),
                rs.getString("order_no"),
                rs.getLong("clinic_id"),
                rs.getObject("doctor_user_id", Long.class),
                rs.getObject("cs_user_id", Long.class),
                rs.getString("product_type"),
                rs.getString("internal_status"),
                rs.getObject("created_at", java.time.LocalDateTime.class));
    }

    public record OrderRow(
            long orderId,
            String orderNo,
            long clinicId,
            Long doctorUserId,
            Long csUserId,
            String productType,
            String internalStatus,
            java.time.LocalDateTime createdAt) {
    }

    private record NotificationPayload(String event, long orderId, String orderNo, String message) {
    }
}
